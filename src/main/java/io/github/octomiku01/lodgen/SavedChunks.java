package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Knows which chunks of a dimension already exist as real chunks on disk. Those already have (or will get) accurate
 * LODs from the real chunk, possibly with the player's builds, and must not be overwritten by plain terrain.
 * <p>
 * Reads only the 4 KiB header of each region file: one offset per chunk, 0 means the chunk was never saved.
 * Headers are re-read after a while, since the game keeps saving chunks while it runs. Not thread safe.
 */
final class SavedChunks {
    private static final long MAX_AGE_MILLIS = 60_000;

    private record Header(long[] present, long readAt) {
    }

    private final Path regionDir;
    private final Long2ObjectOpenHashMap<Header> headers = new Long2ObjectOpenHashMap<>();

    SavedChunks(Path regionDir) {
        this.regionDir = regionDir;
    }

    boolean exists(int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = LodGenerator.pack(regionX, regionZ);
        long now = System.currentTimeMillis();
        Header header = this.headers.get(key);
        if (header == null || now - header.readAt > MAX_AGE_MILLIS) {
            header = new Header(this.read(regionX, regionZ), now);
            this.headers.put(key, header);
        }
        int index = (chunkX & 31) | (chunkZ & 31) << 5;
        return (header.present[index >> 6] & 1L << (index & 63)) != 0;
    }

    private long[] read(int regionX, int regionZ) {
        long[] present = new long[1024 / 64];
        Path file = this.regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        ByteBuffer offsets = ByteBuffer.allocate(4096);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (offsets.hasRemaining() && channel.read(offsets) != -1) {
                // read the whole header
            }
        } catch (NoSuchFileException e) {
            return present;
        } catch (IOException e) {
            LodGen.LOGGER.warn("Could not read region header {}", file, e);
            // Treat the region as fully saved: better no LOD than overwriting real chunks
            Arrays.fill(present, -1L);
            return present;
        }
        offsets.flip();
        for (int index = 0; offsets.remaining() >= 4; index++) {
            if (offsets.getInt() != 0) {
                present[index >> 6] |= 1L << (index & 63);
            }
        }
        return present;
    }
}
