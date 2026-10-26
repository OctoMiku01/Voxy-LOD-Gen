package io.github.octomiku01.lodgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Rough LODs for the horizon beyond the radius: no world generation, only the height of the ground every few blocks
 * (from the generator's noise, by far the most of the cost) and the biome there. The columns get stone, a filler and a top block that fits the
 * biome, water up to the sea level, and a layer of leaves where forests grow. Far cheaper than a real LOD, and
 * replaced by one as soon as it is built. One builder per thread.
 */
final class LodHorizonBuilder {
    /** How deep the filler (dirt, sand...) reaches below the top block */
    private static final int FILLER_DEPTH = 3;

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final BiomeResolver biomes;
    private final int seaLevel;
    private final int minY;

    LodHorizonBuilder(ServerLevel level) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.biomes = this.generator.getBiomeSource().createUncachedResolver(this.randomState);
        this.seaLevel = this.generator.getSeaLevel();
        this.minY = level.getMinY();
    }

    /** What a sampled square looks like */
    private record Column(int ground, BlockState top, BlockState filler, boolean canopy, BlockState leaves, boolean frozen) {
    }

    /**
     * @param step blocks between height samples (4, 8 or 16); each sample stands for a square of this size. Further
     *             away the squares can be larger: Voxy shows coarser detail there anyway
     */
    LodChunk build(int chunkX, int chunkZ, int step) {
        int samples = 16 / step;
        Column[] columns = new Column[samples * samples];
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int sx = 0; sx < samples; sx++) {
            for (int sz = 0; sz < samples; sz++) {
                int x = chunkX * 16 + sx * step + step / 2;
                int z = chunkZ * 16 + sz * step + step / 2;
                // The first block above the solid ground, below water if there is some
                int ground = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, this.randomState);
                Column column = this.column(x, z, ground);
                columns[sx * samples + sz] = column;
                lowest = Math.min(lowest, ground);
                highest = Math.max(highest, column.canopy ? ground + 6 : ground);
            }
        }
        highest = Math.max(highest, this.seaLevel);
        // From one section below the lowest ground (the floor seen through water and cliffs) to the highest block
        int minSection = Math.max(this.level.getMinSectionY(), (lowest >> 4) - 1);
        int maxSection = Math.min(this.level.getMaxSectionY(), highest >> 4);
        int count = maxSection - minSection + 1;
        LevelChunkSection[] sections = new LevelChunkSection[count];
        DataLayer[] skyLight = new DataLayer[count];
        DataLayer[] blockLight = new DataLayer[count];
        for (int i = 0; i < count; i++) {
            int sectionY = minSection + i;
            LevelChunkSection section = new LevelChunkSection(this.level.palettedContainerFactory());
            DataLayer light = new DataLayer();
            for (int sx = 0; sx < samples; sx++) {
                for (int sz = 0; sz < samples; sz++) {
                    Column column = columns[sx * samples + sz];
                    for (int dy = 0; dy < 16; dy++) {
                        int y = (sectionY << 4) + dy;
                        BlockState state = this.blockAt(column, y, Math.floorDiv(chunkX * 16, step) + sx, Math.floorDiv(chunkZ * 16, step) + sz);
                        int sky = y >= column.ground ? 15 : 0;
                        for (int bx = sx * step; bx < sx * step + step; bx++) {
                            for (int bz = sz * step; bz < sz * step + step; bz++) {
                                if (!state.isAir()) {
                                    section.setBlockState(bx, dy, bz, state, false);
                                }
                                light.set(bx, dy, bz, sky);
                            }
                        }
                    }
                }
            }
            sections[i] = section;
            skyLight[i] = light;
        }
        return new LodChunk(chunkX, chunkZ, minSection, sections, skyLight, blockLight);
    }

    /** @param squareX which square along x it is, counted in squares of the sampling step; likewise squareZ */
    private BlockState blockAt(Column column, int y, int squareX, int squareZ) {
        int ground = column.ground;
        if (y < ground - 1 - FILLER_DEPTH) {
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        if (y < ground - 1) {
            return column.filler;
        }
        if (y == ground - 1) {
            return column.top;
        }
        if (y < this.seaLevel) {
            return column.frozen && y == this.seaLevel - 1 ? Blocks.ICE.defaultBlockState() : Blocks.WATER.defaultBlockState();
        }
        // Trees as a rough canopy on about half of the squares, so forests look darker than plains
        if (column.canopy && ground >= this.seaLevel && y >= ground + 3 && y <= ground + 5 && ((squareX ^ squareZ) & 1) == 0) {
            return column.leaves;
        }
        return Blocks.AIR.defaultBlockState();
    }

    private Column column(int x, int z, int ground) {
        int surface = Math.max(ground, this.minY);
        Holder<Biome> biome = this.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z));
        boolean underwater = ground < this.seaLevel;
        boolean snowy = biome.value().coldEnoughToSnow(new BlockPos(x, Math.max(surface, this.seaLevel), z), this.seaLevel);
        BlockState top;
        BlockState filler = Blocks.DIRT.defaultBlockState();
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            top = Blocks.RED_SAND.defaultBlockState();
            filler = Blocks.TERRACOTTA.defaultBlockState();
        } else if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BEACH) && !snowy) {
            top = Blocks.SAND.defaultBlockState();
            filler = Blocks.SANDSTONE.defaultBlockState();
        } else if (underwater) {
            top = biome.is(BiomeTags.IS_DEEP_OCEAN) ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        } else if (snowy) {
            top = Blocks.SNOW_BLOCK.defaultBlockState();
        } else if (biome.is(Biomes.MUSHROOM_FIELDS)) {
            top = Blocks.MYCELIUM.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_MOUNTAIN) && ground > this.seaLevel + 100) {
            top = Blocks.STONE.defaultBlockState();
            filler = Blocks.STONE.defaultBlockState();
        } else {
            top = Blocks.GRASS_BLOCK.defaultBlockState();
        }
        boolean canopy = !underwater && (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_JUNGLE));
        BlockState leaves = biome.is(BiomeTags.IS_TAIGA) ? Blocks.SPRUCE_LEAVES.defaultBlockState()
                : biome.is(BiomeTags.IS_JUNGLE) ? Blocks.JUNGLE_LEAVES.defaultBlockState()
                : Blocks.OAK_LEAVES.defaultBlockState();
        return new Column(ground, top, filler, canopy, leaves, snowy && underwater);
    }
}
