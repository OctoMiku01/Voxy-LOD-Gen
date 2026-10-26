package io.github.octomiku01.lodgen;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Takes the {@link HorizonSamples} of a chunk from the world generator: no world generation, only the height of the
 * ground at the grid corners (from the generator's noise, by far the most of the cost) and the biome there. One per
 * thread; the heights are shared through a cache, neighbouring chunks share their corners.
 */
final class LodHorizonSampler {
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final BiomeSource biomes;
    /** Height of the ground at grid corners, by block position; shared by the samplers of all threads */
    private final ChunkCache<Integer> heights;
    /** Vanilla's quick guess of the surface height, see {@link Estimate} */
    private final DensityFunction quickSurface;
    private final Estimate estimate;

    LodHorizonSampler(ServerLevel level, ChunkCache<Integer> heights, Estimate estimate) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.biomes = this.generator.getBiomeSource();
        this.heights = heights;
        this.quickSurface = this.randomState.router().preliminarySurfaceLevel();
        this.estimate = estimate;
    }

    /**
     * On 26.2 the exact height of the ground (vanilla walks down the noise column) costs about 2 ms, a hundred times
     * the quick guess vanilla uses to find the surface. That guess is off by a fairly steady amount, which depends on
     * the world's noise settings (worldgen mods): it is learned wherever the exact height is needed anyway, mostly close
     * to the radius, and the corrected guess is used further away, where a few blocks do not show. The further away, the
     * more it may scatter: up to the blocks between two grid corners, which the coarse grid smooths over anyway.
     */
    private int height(int x, int z, int step) {
        if (step > 4) {
            int offset = this.estimate.offset(step);
            if (offset != Estimate.UNKNOWN) {
                return this.quickSurface(x, z) + offset;
            }
        }
        // Learned from every exact height, also further away: after a restart the rings close to the radius are done
        int exact = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, this.randomState);
        this.estimate.learn(exact - this.quickSurface(x, z));
        return exact;
    }

    private int quickSurface(int x, int z) {
        return (int) Math.round(this.quickSurface.compute(new DensityFunction.SinglePointContext(x, 0, z)));
    }

    /**
     * How far the quick guess of the surface is off from the exact height in one dimension, shared by all threads.
     * Only used once enough samples agree, and only on a grid at least as coarse as the guess scatters; if it scatters
     * more (some generators), the exact height stays.
     */
    static final class Estimate {
        static final int UNKNOWN = Integer.MIN_VALUE;
        private static final int NEEDED = 200;
        /** The coarsest grid; if the guess scatters more than its step, it is not used at all */
        private static final int COARSEST_STEP = 16;

        private long count;
        private double sum;
        private double sumSquares;
        /** What was last logged: the finest step the guess is used for, 0 for none; -1 before enough samples */
        private int finestStep = -1;

        synchronized void learn(int difference) {
            this.count++;
            this.sum += difference;
            this.sumSquares += (double) difference * difference;
        }

        /** @return the offset to add to the quick guess on a grid with this step, or UNKNOWN if it is not good enough */
        synchronized int offset(int step) {
            if (this.count < NEEDED) {
                return UNKNOWN;
            }
            double mean = this.sum / this.count;
            // Standard deviation of the error, in blocks; the guess is used where the grid corners are further apart
            double spread = Math.sqrt(Math.max(0, this.sumSquares / this.count - mean * mean));
            int finest = 0;
            for (int candidate = COARSEST_STEP; candidate > 4 && spread <= candidate; candidate /= 2) {
                finest = candidate;
            }
            if (finest != this.finestStep) {
                this.finestStep = finest;
                LodGen.LOGGER.info("Horizon: the quick height guess is off by {} blocks, spread {} blocks over {} samples: {}",
                        String.format("%.1f", mean), String.format("%.1f", spread), this.count,
                        finest == 0 ? "not used, the exact height stays"
                                : "used where the grid corners are " + finest + " or more blocks apart");
            }
            return finest != 0 && step >= finest ? (int) Math.round(mean) : UNKNOWN;
        }
    }

    /**
     * @param step blocks between the grid corners (4, 8 or 16). Further away the grid can be coarser: Voxy shows
     *             coarser detail there anyway
     */
    HorizonSamples sample(int chunkX, int chunkZ, int step) {
        int corners = HorizonSamples.corners(step);
        int[] heights = new int[corners * corners];
        Holder<Biome>[] biomes = HorizonSamples.newBiomes(corners * corners);
        for (int i = 0; i < corners; i++) {
            for (int j = 0; j < corners; j++) {
                int x = chunkX * 16 + i * step;
                int z = chunkZ * 16 + j * step;
                // The first block above the solid ground, below water if there is some
                int height = this.heights.get(LodGenerator.pack(x, z), key -> this.height(x, z, step));
                int surface = Math.max(height, this.level.getMinY());
                heights[i * corners + j] = height;
                biomes[i * corners + j] = this.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z),
                        this.randomState.sampler());
            }
        }
        return new HorizonSamples(step, heights, biomes);
    }
}
