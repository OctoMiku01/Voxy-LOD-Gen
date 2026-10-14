package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Knows which chunks of a dimension already exist as fully generated chunks on disk. Those already have (or will get)
 * accurate LODs from the real chunk, possibly with the player's builds, and must not be overwritten by plain terrain.
 * <p>
 * Partially generated chunks do not count: the game saves many of them around explored areas (e.g. with only their
 * structure starts), they have no terrain yet, and nothing else could provide an LOD for them.
 * <p>
 * Reads the region file headers (offset and timestamp per chunk) and the status of a chunk only when its timestamp
 * changed. Headers are re-read after a while, since the game keeps saving chunks while it runs. Not thread safe.
 */
final class SavedChunks {
    private static final long MAX_AGE_MILLIS = 60_000;
    private static final int SECTOR_BYTES = 4096;
    private static final int EXTERNAL_FLAG = 128;
    /** NBT: string tag, name length 6, "Status" */
    private static final byte[] STATUS_TAG = {8, 0, 6, 'S', 't', 'a', 't', 'u', 's'};
    private static final byte[] FULL_STATUS = "minecraft:full".getBytes(StandardCharsets.UTF_8);

    private static final class Region {
        final long[] full = new long[1024 / 64];
        final int[] offsets = new int[1024];
        final int[] timestamps = new int[1024];
        long readAt;
    }

    private final Path regionDir;
    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();

    SavedChunks(Path regionDir) {
        this.regionDir = regionDir;
    }

    boolean exists(int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = LodGenerator.pack(regionX, regionZ);
        long now = System.currentTimeMillis();
        Region region = this.regions.get(key);
        if (region == null || now - region.readAt > MAX_AGE_MILLIS) {
            region = this.read(regionX, regionZ, region);
            region.readAt = now;
            this.regions.put(key, region);
        }
        int index = (chunkX & 31) | (chunkZ & 31) << 5;
        return (region.full[index >> 6] & 1L << (index & 63)) != 0;
    }

    private Region read(int regionX, int regionZ, Region previous) {
        Region region = new Region();
        Path file = this.regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(2 * SECTOR_BYTES);
            readFully(channel, header, 0);
            header.flip();
            if (header.remaining() < 2 * SECTOR_BYTES) {
                return region;
            }
            for (int index = 0; index < 1024; index++) {
                region.offsets[index] = header.getInt(index * 4);
                region.timestamps[index] = header.getInt(SECTOR_BYTES + index * 4);
            }
            for (int index = 0; index < 1024; index++) {
                int offset = region.offsets[index];
                if (offset == 0) {
                    continue;
                }
                boolean full;
                if (previous != null && previous.offsets[index] == offset && previous.timestamps[index] == region.timestamps[index]) {
                    full = (previous.full[index >> 6] & 1L << (index & 63)) != 0;
                } else {
                    full = isFull(channel, offset);
                }
                if (full) {
                    region.full[index >> 6] |= 1L << (index & 63);
                }
            }
        } catch (NoSuchFileException e) {
            return region;
        } catch (IOException e) {
            LodGen.LOGGER.warn("Could not read region file {}", file, e);
            // Treat the region as fully saved: better no LOD than overwriting real chunks
            Arrays.fill(region.full, -1L);
        }
        return region;
    }

    /** Anything that cannot be read counts as a real chunk */
    private static boolean isFull(FileChannel channel, int offset) {
        try {
            long start = (long) (offset >>> 8) * SECTOR_BYTES;
            ByteBuffer prefix = ByteBuffer.allocate(5);
            readFully(channel, prefix, start);
            prefix.flip();
            if (prefix.remaining() < 5) {
                return true;
            }
            int length = prefix.getInt();
            int compression = prefix.get() & 0xFF;
            // Very large chunks are stored in a separate file: certainly a real chunk
            if ((compression & EXTERNAL_FLAG) != 0 || length <= 1 || length > (offset & 0xFF) * SECTOR_BYTES) {
                return true;
            }
            RegionFileVersion version = RegionFileVersion.fromId(compression);
            if (version == null) {
                return true;
            }
            ByteBuffer data = ByteBuffer.allocate(length - 1);
            readFully(channel, data, start + 5);
            try (InputStream in = version.wrap(new ByteArrayInputStream(data.array(), 0, data.position()))) {
                return isFullStatus(in.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    // The status is a top level string tag; no need to parse the whole chunk
    private static boolean isFullStatus(byte[] nbt) {
        outer:
        for (int i = 0; i + STATUS_TAG.length + 2 <= nbt.length; i++) {
            for (int j = 0; j < STATUS_TAG.length; j++) {
                if (nbt[i + j] != STATUS_TAG[j]) {
                    continue outer;
                }
            }
            int at = i + STATUS_TAG.length;
            int length = (nbt[at] & 0xFF) << 8 | nbt[at + 1] & 0xFF;
            return length == FULL_STATUS.length && at + 2 + length <= nbt.length
                    && Arrays.equals(nbt, at + 2, at + 2 + length, FULL_STATUS, 0, length);
        }
        // No status at all: an unknown format, so assume it is real
        return true;
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }
    }
}
