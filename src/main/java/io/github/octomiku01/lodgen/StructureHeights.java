package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Remembers the terrain heights structure placement asks for, per worker. Finding where a structure starts computes
 * the terrain column there from noise, and structure sets with several structures (like Trek's monster rooms, in
 * almost every chunk) try one after the other at nearly the same spot, each asking again. A column always has the
 * same height, so the result does not change. Only used while {@link LodStructures} finds structure starts.
 */
public final class StructureHeights {
    public static final int UNKNOWN = Integer.MIN_VALUE;
    /** Columns remembered per worker; consecutive chunks are close, so recent ones are what gets asked again */
    private static final int CAPACITY = 8192;
    private static final ThreadLocal<StructureHeights> CURRENT = ThreadLocal.withInitial(StructureHeights::new);

    private final Long2IntOpenHashMap heights = new Long2IntOpenHashMap();
    private int depth;
    private Object generator;
    private RandomState randomState;
    private int minY;
    private int height;

    private StructureHeights() {
        this.heights.defaultReturnValue(UNKNOWN);
    }

    static void begin() {
        CURRENT.get().depth++;
    }

    static void end() {
        CURRENT.get().depth--;
    }

    public static int get(Object generator, int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        StructureHeights cache = CURRENT.get();
        if (cache.depth == 0 || !cache.matches(generator, heightAccessor, randomState)) {
            return UNKNOWN;
        }
        return cache.heights.get(key(x, z, type));
    }

    public static void put(Object generator, int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState, int height) {
        StructureHeights cache = CURRENT.get();
        if (cache.depth == 0) {
            return;
        }
        if (!cache.matches(generator, heightAccessor, randomState) || cache.heights.size() >= CAPACITY) {
            // Another dimension, or full
            cache.heights.clear();
            cache.generator = generator;
            cache.randomState = randomState;
            cache.minY = heightAccessor.getMinY();
            cache.height = heightAccessor.getHeight();
        }
        cache.heights.put(key(x, z, type), height);
    }

    private boolean matches(Object generator, LevelHeightAccessor heightAccessor, RandomState randomState) {
        return this.generator == generator && this.randomState == randomState && this.minY == heightAccessor.getMinY() && this.height == heightAccessor.getHeight();
    }

    private static long key(int x, int z, Heightmap.Types type) {
        // Block coordinates fit in 26 bits each
        return (long) x & 0x3FFFFFFL | ((long) z & 0x3FFFFFFL) << 26 | (long) type.ordinal() << 52;
    }
}
