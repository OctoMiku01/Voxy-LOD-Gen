package io.github.octomiku01.lodgen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Where the structures are that the LODs show, for the 3D map: noted whenever the generation decides which structures
 * start in a chunk (also for terrain-only LODs, whose ground is flattened under them), and from the explored chunks
 * read from the save. Kept per dimension in {@code <world>/lodgen/structures/}, so they survive restarts without
 * generating anything again.
 * <p>
 * Thread safe: workers note structures, the server thread reads them, and a thread of its own saves them.
 */
public final class StructureMarkers {
    /** 2 adds the regions {@link StructureScan} went through */
    private static final int FORMAT = 2;
    /**
     * A structure that starts more often than this in one region of 32 x 32 chunks is left off the map: it would cover
     * it (like Trek's monster rooms, in almost every chunk). Vanilla's most common ones, ocean ruins and shipwrecks,
     * have at most about four.
     */
    private static final int TOO_COMMON = 8;
    /** Too many and too small to be worth a marker, mostly hidden underground */
    private static final Set<String> LEFT_OUT = Set.of("minecraft:mineshaft", "minecraft:mineshaft_mesa", "minecraft:buried_treasure",
            "minecraft:nether_fossil");

    /** @param id the structure, e.g. minecraft:village_plains; x, y, z where it is, on the ground where it has one */
    public record Marker(String id, int x, int y, int z) {
    }

    private static final class Store {
        final Path file;
        /** By the chunk the structure starts in */
        final Map<Long, Marker> markers = new ConcurrentHashMap<>();
        /** Regions of 32 x 32 chunks whose structures were all found; guarded by itself */
        final LongOpenHashSet scanned = new LongOpenHashSet();
        /** How many of each structure start in each region, to find those too common to show; guarded by itself */
        final Map<String, Long2IntOpenHashMap> perRegion = new HashMap<>();
        /** Structures too common for the map, see TOO_COMMON; each change counts, so players get the map again without them */
        final Set<String> tooCommon = ConcurrentHashMap.newKeySet();
        volatile int tooCommonVersion;
        volatile boolean dirty;

        void count(long chunk, String id) {
            synchronized (this.perRegion) {
                long region = LodGenerator.pack(ChunkPos.getX(chunk) >> 5, ChunkPos.getZ(chunk) >> 5);
                int count = this.perRegion.computeIfAbsent(id, key -> new Long2IntOpenHashMap()).addTo(region, 1) + 1;
                if (count > TOO_COMMON && this.tooCommon.add(id)) {
                    this.tooCommonVersion++;
                    LodGen.LOGGER.info("Structure {} is too common for the 3D map (more than {} in a region of 32 x 32 chunks), left out", id, TOO_COMMON);
                }
            }
        }

        Store(Path file) {
            this.file = file;
            this.load();
        }

        private void load() {
            if (!Files.exists(this.file)) {
                return;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(this.file))))) {
                int format = in.readInt();
                if (format != 1 && format != FORMAT) {
                    return;
                }
                int ids = in.readInt();
                String[] palette = new String[ids];
                for (int i = 0; i < ids; i++) {
                    palette[i] = in.readUTF();
                }
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    long chunk = in.readLong();
                    this.markers.put(chunk, new Marker(palette[in.readInt()], in.readInt(), in.readInt(), in.readInt()));
                }
                this.markers.forEach((chunk, marker) -> this.count(chunk, marker.id()));
                if (format >= 2) {
                    int regions = in.readInt();
                    for (int i = 0; i < regions; i++) {
                        this.scanned.add(in.readLong());
                    }
                }
            } catch (IOException | RuntimeException e) {
                LodGen.LOGGER.warn("Could not read {}, its structures are noted again as LODs are generated", this.file, e);
            }
        }

        void save() {
            if (!this.dirty) {
                return;
            }
            this.dirty = false;
            try {
                Files.createDirectories(this.file.getParent());
                Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
                // Not Map.copyOf: a Long chunk key hashes to x ^ z, so many collide, and its probing then takes
                // quadratic time (froze the server for half a minute with many markers)
                List<Map.Entry<Long, Marker>> copy = new ArrayList<>(this.markers.entrySet());
                long[] regions;
                synchronized (this.scanned) {
                    regions = this.scanned.toLongArray();
                }
                List<String> palette = new ArrayList<>();
                Map<String, Integer> index = new HashMap<>();
                for (Map.Entry<Long, Marker> entry : copy) {
                    index.computeIfAbsent(entry.getValue().id, id -> {
                        palette.add(id);
                        return palette.size() - 1;
                    });
                }
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                    out.writeInt(FORMAT);
                    out.writeInt(palette.size());
                    for (String id : palette) {
                        out.writeUTF(id);
                    }
                    out.writeInt(copy.size());
                    for (Map.Entry<Long, Marker> entry : copy) {
                        Marker marker = entry.getValue();
                        out.writeLong(entry.getKey());
                        out.writeInt(index.get(marker.id));
                        out.writeInt(marker.x);
                        out.writeInt(marker.y);
                        out.writeInt(marker.z);
                    }
                    out.writeInt(regions.length);
                    for (long region : regions) {
                        out.writeLong(region);
                    }
                }
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                this.dirty = true;
                LodGen.LOGGER.error("Could not save {}", this.file, e);
            }
        }
    }

    private static volatile @Nullable MinecraftServer server;
    private static final Map<ResourceKey<Level>, Store> STORES = new ConcurrentHashMap<>();
    /** Writes the stores one after another, so an older save never replaces a newer one */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LOD Gen structure markers");
        thread.setDaemon(true);
        return thread;
    });

    private StructureMarkers() {
    }

    static void start(MinecraftServer started) {
        STORES.clear();
        server = started;
    }

    static void stop() {
        // Waits for the saves still queued, then writes what changed since
        List<Store> stores = new ArrayList<>(STORES.values());
        try {
            WRITER.submit(() -> stores.forEach(Store::save)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LodGen.LOGGER.error("Could not save the structure markers", e);
        }
        STORES.clear();
        server = null;
    }

    /** Server thread, every minute: written on {@link #WRITER}, so the server does not wait for it */
    static void save() {
        for (Store store : STORES.values()) {
            WRITER.execute(store::save);
        }
    }

    private static @Nullable Store storeOf(ServerLevel level) {
        if (server != level.getServer()) {
            // A builder of a world that is closing, or LODs built without a running generator
            return null;
        }
        return STORES.computeIfAbsent(level.dimension(), key -> {
            Identifier id = key.identifier();
            return new Store(level.getServer().getWorldPath(LevelResource.ROOT).resolve("lodgen").resolve("structures")
                    .resolve(id.getNamespace()).resolve(id.getPath() + ".dat"));
        });
    }

    /** Whether a structure with this id gets a marker */
    private static boolean wanted(String id) {
        return !LEFT_OUT.contains(id);
    }

    /**
     * A structure the generation decided on. Called while structure starts are found, where terrain heights are
     * remembered (see {@link StructureHeights}), so finding the ground costs little.
     */
    static void note(ServerLevel level, StructureStart start) {
        if (!start.isValid()) {
            return;
        }
        Store store = storeOf(level);
        if (store == null) {
            return;
        }
        long chunk = start.getChunkPos().pack();
        if (store.markers.containsKey(chunk)) {
            return;
        }
        String id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey(start.getStructure())
                .map(key -> key.identifier().toString()).orElse(null);
        if (id == null || !wanted(id)) {
            return;
        }
        BoundingBox box = start.getBoundingBox();
        int x = box.getCenter().getX();
        int z = box.getCenter().getZ();
        put(store, chunk, new Marker(id, x, groundAt(level, x, z, box), z));
    }

    /** The structures in a real chunk read from the save, from its tag; mostly already noted, unless explored earlier */
    static void noteSaved(ServerLevel level, CompoundTag chunkTag) {
        Store store = storeOf(level);
        if (store == null) {
            return;
        }
        CompoundTag starts = chunkTag.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
        for (String key : starts.keySet()) {
            CompoundTag start = starts.getCompoundOrEmpty(key);
            String id = start.getStringOr("id", "INVALID");
            if (id.equals("INVALID") || !wanted(id)) {
                continue;
            }
            long chunk = ChunkPos.pack(start.getIntOr("ChunkX", 0), start.getIntOr("ChunkZ", 0));
            if (store.markers.containsKey(chunk)) {
                continue;
            }
            BoundingBox box = null;
            for (Tag child : start.getListOrEmpty("Children")) {
                int[] bb = child instanceof CompoundTag piece ? piece.getIntArray("BB").orElse(null) : null;
                if (bb != null && bb.length == 6) {
                    BoundingBox pieceBox = new BoundingBox(bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]);
                    box = box == null ? pieceBox : box.encapsulate(pieceBox);
                }
            }
            if (box != null) {
                int x = box.getCenter().getX();
                int z = box.getCenter().getZ();
                put(store, chunk, new Marker(id, x, groundAt(level, x, z, box), z));
            }
        }
    }

    /** Grows whenever a structure turns out too common for the map: players who got it are sent the map again */
    static int tooCommonVersion(ServerLevel level) {
        Store store = storeOf(level);
        return store == null ? 0 : store.tooCommonVersion;
    }

    /** Whether {@link StructureScan} already found all structures of this region of 32 x 32 chunks */
    static boolean isScanned(ServerLevel level, long region) {
        Store store = storeOf(level);
        if (store == null) {
            return true;
        }
        synchronized (store.scanned) {
            return store.scanned.contains(region);
        }
    }

    static void markScanned(ServerLevel level, long region) {
        Store store = storeOf(level);
        if (store != null) {
            synchronized (store.scanned) {
                store.scanned.add(region);
            }
            store.dirty = true;
        }
    }

    private static void put(Store store, long chunk, Marker marker) {
        if (store.markers.putIfAbsent(chunk, marker) == null) {
            store.dirty = true;
            store.count(chunk, marker.id());
        }
    }

    /**
     * Where the marker sits: on the ground above the structure, so ones underground show where to dig. Under a
     * ceiling (the Nether) the ground is the roof, there the structure's own middle counts.
     */
    private static int groundAt(ServerLevel level, int x, int z, BoundingBox box) {
        if (level.dimensionType().hasCeiling()) {
            return box.getCenter().getY();
        }
        try {
            int ground = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
                    level.getChunkSource().randomState());
            // Structures in the air (End cities on islands, floating ones of mods) stand on what is below them
            return ground > level.getMinY() ? Math.max(ground, box.minY()) : box.minY();
        } catch (RuntimeException e) {
            return box.maxY();
        }
    }

    /**
     * The markers within this square of chunks around a point that the player was not sent yet, nearest to another
     * point first.
     *
     * @param sent the start chunks the player already got in this dimension; those returned are added
     */
    static List<Marker> unsent(ServerLevel level, int chunkX, int chunkZ, int radius, int nearX, int nearZ, LongSet sent, int max,
            BorderBounds border) {
        Store store = storeOf(level);
        if (store == null) {
            return List.of();
        }
        List<Map.Entry<Long, Marker>> found = new ArrayList<>();
        for (Map.Entry<Long, Marker> entry : store.markers.entrySet()) {
            long chunk = entry.getKey();
            int x = ChunkPos.getX(chunk);
            int z = ChunkPos.getZ(chunk);
            if (Math.abs(x - chunkX) <= radius && Math.abs(z - chunkZ) <= radius && border.contains(x, z) && !sent.contains(chunk)
                    && !store.tooCommon.contains(entry.getValue().id())) {
                found.add(entry);
            }
        }
        found.sort(Comparator.comparingLong(entry -> {
            long dx = ChunkPos.getX(entry.getKey()) - nearX;
            long dz = ChunkPos.getZ(entry.getKey()) - nearZ;
            return dx * dx + dz * dz;
        }));
        List<Marker> result = new ArrayList<>(Math.min(max, found.size()));
        for (int i = 0; i < found.size() && result.size() < max; i++) {
            sent.add(found.get(i).getKey().longValue());
            result.add(found.get(i).getValue());
        }
        return result;
    }
}
