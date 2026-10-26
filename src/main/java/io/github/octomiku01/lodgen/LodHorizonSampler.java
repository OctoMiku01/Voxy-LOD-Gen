package io.github.octomiku01.lodgen;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
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
    private final BiomeResolver biomes;
    /** Height of the ground at grid corners, by block position; shared by the samplers of all threads */
    private final ChunkCache<Integer> heights;

    LodHorizonSampler(ServerLevel level, ChunkCache<Integer> heights) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.biomes = this.generator.getBiomeSource().createUncachedResolver(this.randomState);
        this.heights = heights;
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
                int height = this.heights.get(LodGenerator.pack(x, z),
                        key -> this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, this.randomState));
                int surface = Math.max(height, this.level.getMinY());
                heights[i * corners + j] = height;
                biomes[i * corners + j] = this.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z));
            }
        }
        return new HorizonSamples(step, heights, biomes);
    }
}
