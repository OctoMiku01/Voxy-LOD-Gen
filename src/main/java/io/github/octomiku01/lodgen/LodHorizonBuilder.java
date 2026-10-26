package io.github.octomiku01.lodgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Rough LODs for the horizon beyond the radius: no world generation, only the height of the ground at the corners of a
 * grid (from the generator's noise, by far the most of the cost) and the biome there. Between the corners the height
 * is interpolated block by block, so the ground forms slopes instead of flat squares with walls between them, and
 * neighbouring chunks meet without a seam. The columns get stone, a filler and a top block that fits the biome, water
 * up to the sea level, and a layer of leaves where forests grow. Far cheaper than a real LOD, and replaced by one as
 * soon as it is built. One builder per thread; the heights are shared through a cache.
 */
final class LodHorizonBuilder {
    /** How deep the filler (dirt, sand...) reaches below the top block */
    private static final int FILLER_DEPTH = 3;

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final BiomeSource biomes;
    /** Height of the ground at grid corners, by block position; shared by the builders of all threads */
    private final ChunkCache<Integer> heights;
    /** Vanilla's quick guess of the surface height, see {@link Estimate} */
    private final DensityFunction quickSurface;
    private final Estimate estimate;
    private final int seaLevel;
    private final int minY;

    LodHorizonBuilder(ServerLevel level, ChunkCache<Integer> heights, Estimate estimate) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.biomes = this.generator.getBiomeSource();
        this.heights = heights;
        this.quickSurface = this.randomState.router().preliminarySurfaceLevel();
        this.estimate = estimate;
        this.seaLevel = this.generator.getSeaLevel();
        this.minY = level.getMinY();
    }

    /**
     * On 26.2 the exact height of the ground (vanilla walks down the noise column) costs about 2 ms, a hundred times
     * the quick guess vanilla uses to find the surface. That guess is off by a fairly steady amount, which depends on
     * the world's noise settings (worldgen mods): it is learned where the exact height is needed anyway, close to the
     * radius, and the corrected guess is used further away, where a few blocks do not show.
     */
    private int height(int x, int z, int step) {
        if (step > 4) {
            int offset = this.estimate.offset();
            if (offset != Estimate.UNKNOWN) {
                return this.quickSurface(x, z) + offset;
            }
        }
        int exact = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, this.randomState);
        if (step == 4) {
            this.estimate.learn(exact - this.quickSurface(x, z));
        }
        return exact;
    }

    private int quickSurface(int x, int z) {
        return (int) Math.round(this.quickSurface.compute(new DensityFunction.SinglePointContext(x, 0, z)));
    }

    /**
     * How far the quick guess of the surface is off from the exact height in one dimension, shared by all threads.
     * Only used once enough samples agree; if the guess scatters too much (some generators), the exact height stays.
     */
    static final class Estimate {
        static final int UNKNOWN = Integer.MIN_VALUE;
        private static final int NEEDED = 200;
        /** Standard deviation of the error, in blocks, up to which the guess is used */
        private static final double MAX_SPREAD = 8;

        private long count;
        private double sum;
        private double sumSquares;

        synchronized void learn(int difference) {
            this.count++;
            this.sum += difference;
            this.sumSquares += (double) difference * difference;
        }

        synchronized int offset() {
            if (this.count < NEEDED) {
                return UNKNOWN;
            }
            double mean = this.sum / this.count;
            double spread = Math.sqrt(Math.max(0, this.sumSquares / this.count - mean * mean));
            return spread <= MAX_SPREAD ? (int) Math.round(mean) : UNKNOWN;
        }
    }

    /** The blocks of the ground around a grid corner */
    private record Ground(BlockState top, BlockState filler, BlockState underwater, boolean canopy, BlockState leaves, boolean frozen) {
    }

    /**
     * @param step blocks between the grid corners (4, 8 or 16). Further away the grid can be coarser: Voxy shows
     *             coarser detail there anyway
     */
    LodChunk build(int chunkX, int chunkZ, int step) {
        int corners = 16 / step + 1;
        int[] cornerHeight = new int[corners * corners];
        Ground[] cornerGround = new Ground[corners * corners];
        for (int i = 0; i < corners; i++) {
            for (int j = 0; j < corners; j++) {
                int x = chunkX * 16 + i * step;
                int z = chunkZ * 16 + j * step;
                // The first block above the solid ground, below water if there is some
                int height = this.heights.get(LodGenerator.pack(x, z), key -> this.height(x, z, step));
                cornerHeight[i * corners + j] = height;
                cornerGround[i * corners + j] = this.ground(x, z, height);
            }
        }

        // Each block column: its height interpolated between the four corners around it, its blocks from the nearest
        int[] ground = new int[256];
        Ground[] kind = new Ground[256];
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int bx = 0; bx < 16; bx++) {
            int i = bx / step;
            float fx = (bx % step) / (float) step;
            for (int bz = 0; bz < 16; bz++) {
                int j = bz / step;
                float fz = (bz % step) / (float) step;
                float top = cornerHeight[i * corners + j] * (1 - fx) + cornerHeight[(i + 1) * corners + j] * fx;
                float bottom = cornerHeight[i * corners + j + 1] * (1 - fx) + cornerHeight[(i + 1) * corners + j + 1] * fx;
                int height = Math.round(top * (1 - fz) + bottom * fz);
                Ground near = cornerGround[(i + Math.round(fx)) * corners + j + Math.round(fz)];
                ground[bx * 16 + bz] = height;
                kind[bx * 16 + bz] = near;
                lowest = Math.min(lowest, height);
                highest = Math.max(highest, near.canopy ? height + 6 : height);
            }
        }
        highest = Math.max(highest, this.seaLevel);

        // From one section below the lowest ground (the floor seen through water) to the highest block
        int minSection = Math.max(this.level.getMinSectionY(), (lowest >> 4) - 1);
        int maxSection = Math.min(this.level.getMaxSectionY(), highest >> 4);
        int count = maxSection - minSection + 1;
        LevelChunkSection[] sections = new LevelChunkSection[count];
        DataLayer[] skyLight = new DataLayer[count];
        DataLayer[] blockLight = new DataLayer[count];
        for (int s = 0; s < count; s++) {
            int sectionY = minSection + s;
            LevelChunkSection section = new LevelChunkSection(this.level.palettedContainerFactory());
            DataLayer light = new DataLayer();
            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    int height = ground[bx * 16 + bz];
                    Ground near = kind[bx * 16 + bz];
                    int crown = near.canopy ? crown(chunkX * 16 + bx, chunkZ * 16 + bz) : 0;
                    for (int dy = 0; dy < 16; dy++) {
                        int y = (sectionY << 4) + dy;
                        BlockState state = this.blockAt(near, height, crown, y);
                        if (!state.isAir()) {
                            section.setBlockState(bx, dy, bz, state, false);
                        }
                        light.set(bx, dy, bz, y >= height ? 15 : 0);
                    }
                }
            }
            sections[s] = section;
            skyLight[s] = light;
        }
        return new LodChunk(chunkX, chunkZ, minSection, sections, skyLight, blockLight);
    }

    /**
     * Rough trees for forests: in each 4 x 4 square of blocks, a tree with a 3 x 3 crown stands at a spot and height
     * that depend on where the square is, so forests look uneven but are the same every time.
     *
     * @return 0 without leaves on this column, else the crown's distance above the ground (low 4 bits) and its
     *         thickness (above)
     */
    private static int crown(int x, int z) {
        long hash = (long) (x >> 2) * 0x9E3779B97F4A7C15L ^ (long) (z >> 2) * 0xC2B2AE3D27D4EB4FL;
        hash ^= hash >>> 29;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 32;
        if ((hash & 0xFF) >= 150) {
            return 0;
        }
        int offsetX = (int) (hash >>> 8 & 1);
        int offsetZ = (int) (hash >>> 9 & 1);
        int localX = (x & 3) - offsetX;
        int localZ = (z & 3) - offsetZ;
        if (localX < 0 || localX > 2 || localZ < 0 || localZ > 2) {
            return 0;
        }
        int above = 3 + (int) (hash >>> 10 & 1);
        int thickness = 2 + (int) (hash >>> 11 & 1);
        return above | thickness << 4;
    }

    /** @param crown the rough tree on this column, see {@link #crown} */
    private BlockState blockAt(Ground near, int height, int crown, int y) {
        boolean underwater = height < this.seaLevel;
        if (y < height - 1 - FILLER_DEPTH) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        if (y < height - 1) {
            return underwater ? near.underwater : near.filler;
        }
        if (y == height - 1) {
            return underwater ? near.underwater : near.top;
        }
        if (y < this.seaLevel) {
            return near.frozen && y == this.seaLevel - 1 ? Blocks.ICE.defaultBlockState() : Blocks.WATER.defaultBlockState();
        }
        if (crown != 0 && !underwater && y >= height + (crown & 15) && y < height + (crown & 15) + (crown >> 4)) {
            return near.leaves;
        }
        return Blocks.AIR.defaultBlockState();
    }

    private Ground ground(int x, int z, int height) {
        int surface = Math.max(height, this.minY);
        Holder<Biome> biome = this.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z), this.randomState.sampler());
        boolean snowy = biome.value().coldEnoughToSnow(new BlockPos(x, Math.max(surface, this.seaLevel), z), this.seaLevel);
        BlockState top;
        BlockState filler = Blocks.DIRT.defaultBlockState();
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            top = Blocks.RED_SAND.defaultBlockState();
            filler = Blocks.TERRACOTTA.defaultBlockState();
        } else if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BEACH) && !snowy) {
            top = Blocks.SAND.defaultBlockState();
            filler = Blocks.SANDSTONE.defaultBlockState();
        } else if (snowy) {
            top = Blocks.SNOW_BLOCK.defaultBlockState();
        } else if (biome.is(Biomes.MUSHROOM_FIELDS)) {
            top = Blocks.MYCELIUM.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_MOUNTAIN) && height > this.seaLevel + 100) {
            top = Blocks.STONE.defaultBlockState();
            filler = Blocks.STONE.defaultBlockState();
        } else {
            top = Blocks.GRASS_BLOCK.defaultBlockState();
        }
        BlockState underwater = biome.is(BiomeTags.IS_DEEP_OCEAN) ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        boolean canopy = biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_JUNGLE);
        BlockState leaves = biome.is(BiomeTags.IS_TAIGA) ? Blocks.SPRUCE_LEAVES.defaultBlockState()
                : biome.is(BiomeTags.IS_JUNGLE) ? Blocks.JUNGLE_LEAVES.defaultBlockState()
                : Blocks.OAK_LEAVES.defaultBlockState();
        return new Ground(top, filler, underwater, canopy, leaves, snowy);
    }
}
