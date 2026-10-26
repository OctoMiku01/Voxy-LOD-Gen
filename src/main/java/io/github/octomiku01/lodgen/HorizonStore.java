package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link HorizonSamples} of one dimension, saved in the world: taken once from the world generator, then reused
 * for every player and after restarts. Kept in region files of 32 x 32 chunks; the regions in use stay in memory in a
 * compact form, as many as the horizon's rings pass through.
 */
final class HorizonStore {
    /** Raised when samples are taken differently: files of an older format are dropped, so all is sampled again */
    private static final int FORMAT = 1;
    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;

    /** The samples of one region; each chunk's encoded with the region's biome palette, see {@link #encode} */
    private static final class Region {
        final byte[][] chunks = new byte[REGION_SIZE * REGION_SIZE][];
        final List<Holder<Biome>> palette = new ArrayList<>();
        final Object2IntOpenHashMap<Holder<Biome>> paletteIndex = new Object2IntOpenHashMap<>();
        boolean dirty;

        int indexOf(Holder<Biome> biome) {
            int index = this.paletteIndex.getOrDefault(biome, -1);
            if (index < 0) {
                index = this.palette.size();
                this.palette.add(biome);
                this.paletteIndex.put(biome, index);
            }
            return index;
        }
    }

    private final Path folder;
    private final Registry<Biome> biomes;
    private final int capacity;
    /** Least recently used first; guarded by this */
    private final LinkedHashMap<Long, Region> regions = new LinkedHashMap<>(16, 0.75f, true);

    /** @param capacity how many regions stay in memory */
    HorizonStore(ServerLevel level, Path folder, int capacity) {
        this.folder = folder;
        this.biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        this.capacity = capacity;
    }

    /** @return the samples of this chunk, if they were taken with this step or a finer one; null otherwise */
    synchronized @Nullable HorizonSamples get(int chunkX, int chunkZ, int step) {
        Region region = this.region(chunkX, chunkZ);
        byte[] data = region.chunks[indexInRegion(chunkX, chunkZ)];
        if (data == null || data[0] > step) {
            return null;
        }
        return decode(data, region.palette);
    }

    synchronized void put(int chunkX, int chunkZ, HorizonSamples samples) {
        Region region = this.region(chunkX, chunkZ);
        region.chunks[indexInRegion(chunkX, chunkZ)] = encode(samples, region);
        region.dirty = true;
    }

    synchronized void save() {
        for (Map.Entry<Long, Region> entry : this.regions.entrySet()) {
            this.save(entry.getKey(), entry.getValue());
        }
    }

    /** Forgets all samples of the dimension, in memory and on disk */
    synchronized void clear() {
        this.regions.clear();
        try (var files = Files.list(this.folder)) {
            for (Iterator<Path> it = files.iterator(); it.hasNext(); ) {
                Files.deleteIfExists(it.next());
            }
        } catch (IOException e) {
            // No folder yet, or a file in use: what is left is overwritten later
        }
    }

    private Region region(int chunkX, int chunkZ) {
        long key = LodGenerator.pack(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        Region region = this.regions.get(key);
        if (region == null) {
            region = this.load(key);
            this.regions.put(key, region);
            if (this.regions.size() > this.capacity) {
                Iterator<Map.Entry<Long, Region>> eldest = this.regions.entrySet().iterator();
                Map.Entry<Long, Region> entry = eldest.next();
                this.save(entry.getKey(), entry.getValue());
                eldest.remove();
            }
        }
        return region;
    }

    private static int indexInRegion(int chunkX, int chunkZ) {
        return (chunkX & REGION_SIZE - 1) * REGION_SIZE + (chunkZ & REGION_SIZE - 1);
    }

    private Path file(long key) {
        return this.folder.resolve("r." + (int) key + "." + (int) (key >>> 32) + ".bin");
    }

    // A step byte, then a short height and a short biome (index in the palette) per corner
    private static byte[] encode(HorizonSamples samples, Region region) {
        int count = samples.heights().length;
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 * count);
        buffer.put((byte) samples.step());
        for (int i = 0; i < count; i++) {
            buffer.putShort((short) samples.heights()[i]);
            buffer.putShort((short) region.indexOf(samples.biomes()[i]));
        }
        return buffer.array();
    }

    private static @Nullable HorizonSamples decode(byte[] data, List<Holder<Biome>> palette) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int step = buffer.get();
        int count = (data.length - 1) / 4;
        int[] heights = new int[count];
        Holder<Biome>[] biomes = HorizonSamples.newBiomes(count);
        for (int i = 0; i < count; i++) {
            heights[i] = buffer.getShort();
            Holder<Biome> biome = palette.get(buffer.getShort());
            if (biome == null) {
                // A biome that no longer exists (a mod was removed): sampled again
                return null;
            }
            biomes[i] = biome;
        }
        return new HorizonSamples(step, heights, biomes);
    }

    private Region load(long key) {
        Region region = new Region();
        Path file = this.file(key);
        if (!Files.exists(file)) {
            return region;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != FORMAT) {
                return region;
            }
            int paletteSize = in.readInt();
            for (int i = 0; i < paletteSize; i++) {
                Identifier id = Identifier.parse(in.readUTF());
                Holder<Biome> biome = this.biomes.get(ResourceKey.create(Registries.BIOME, id)).orElse(null);
                // Kept at its index even if missing, so the indices stay right
                region.palette.add(biome);
                if (biome != null) {
                    region.paletteIndex.put(biome, i);
                }
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int index = in.readUnsignedShort();
                byte[] data = new byte[in.readUnsignedShort()];
                in.readFully(data);
                region.chunks[index] = data;
            }
        } catch (IOException | RuntimeException e) {
            LodGen.LOGGER.warn("Could not read {}, its horizon is sampled again", file, e);
            return new Region();
        }
        return region;
    }

    private void save(long key, Region region) {
        if (!region.dirty) {
            return;
        }
        Path file = this.file(key);
        try {
            Files.createDirectories(this.folder);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(FORMAT);
                out.writeInt(region.palette.size());
                for (Holder<Biome> biome : region.palette) {
                    // A biome missing since loading keeps its index with a name that never matches
                    out.writeUTF(biome == null ? "lodgen:missing" : biome.unwrapKey().map(k -> k.identifier().toString()).orElse("lodgen:missing"));
                }
                int count = 0;
                for (byte[] data : region.chunks) {
                    if (data != null) {
                        count++;
                    }
                }
                out.writeInt(count);
                for (int i = 0; i < region.chunks.length; i++) {
                    if (region.chunks[i] != null) {
                        out.writeShort(i);
                        out.writeShort(region.chunks[i].length);
                        out.write(region.chunks[i]);
                    }
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            region.dirty = false;
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not save {}", file, e);
        }
    }
}
