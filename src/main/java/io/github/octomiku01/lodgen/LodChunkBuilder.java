package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;

/**
 * Generates the terrain of one chunk for an LOD and hands it to Voxy.
 * <p>
 * Only the steps that shape what an LOD shows run here: biomes, noise (base terrain), surface and, for closer LODs,
 * features. Structures, carvers, the light engine, mob spawning and saving are skipped. The chunk never enters the vanilla chunk
 * system, so nothing is loaded, ticked or written to the region files.
 */
final class LodChunkBuilder {
    private static final int MAX_SKY_LIGHT = 15;
    private static final ChunkStep FEATURES_STEP = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);

    private final ServerLevel level;
    private final NoiseBasedChunkGenerator generator;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final StructureManager noStructures;
    private final WorldGenerationContext context;
    private final Set<Holder<Biome>> possibleBiomes;
    private final long biomeZoomSeed;

    private LodChunkBuilder(ServerLevel level, NoiseBasedChunkGenerator generator) {
        this.level = level;
        this.generator = generator;
        this.biomeSource = generator.getBiomeSource();
        this.randomState = level.getChunkSource().randomState();
        this.noStructures = new NoStructures(level);
        this.context = new WorldGenerationContext(generator, level);
        // Surface rules only use this to skip rules for biomes that cannot occur; a superset is always correct
        this.possibleBiomes = this.biomeSource.possibleBiomes();
        this.biomeZoomSeed = BiomeManager.obfuscateSeed(level.getSeed());
    }

    /**
     * @return null if this dimension cannot be generated (not a noise based generator, e.g. superflat)
     */
    static @Nullable LodChunkBuilder create(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return null;
        }
        return new LodChunkBuilder(level, generator);
    }

    /** Terrain only: what distant LODs show */
    LodChunk build(ChunkPos pos) {
        return this.toLodChunk(this.generateTerrain(pos));
    }

    /**
     * Terrain with features (trees, flowers, lakes...) for the {@code size} x {@code size} chunks starting at the given
     * chunk. A feature can reach into the neighbouring chunks, so features run on a ring of chunks around the tile
     * too, and those need the terrain of yet another ring.
     *
     * @param cancelled checked between chunks; a tile takes a few seconds, too long to wait for when the world closes
     */
    List<LodChunk> buildTile(int minX, int minZ, int size, BooleanSupplier cancelled) {
        return this.buildTile(minX, minZ, size, cancelled, false);
    }

    /**
     * @param reversed decorate the chunks in the opposite order; overlapping features (trees growing into each other)
     *                 depend on that order, and {@code /lodgen compare} uses it to measure how much
     */
    List<LodChunk> buildTile(int minX, int minZ, int size, BooleanSupplier cancelled, boolean reversed) {
        Long2ObjectOpenHashMap<ProtoChunk> chunks = new Long2ObjectOpenHashMap<>();
        for (int x = minX - 2; x < minX + size + 2; x++) {
            for (int z = minZ - 2; z < minZ + size + 2; z++) {
                checkCancelled(cancelled);
                chunks.put(LodGenerator.pack(x, z), this.generateTerrain(new ChunkPos(x, z)));
            }
        }
        // The chunks skip the carvers, but count as ready for features like in vanilla
        Long2ObjectOpenHashMap<GenerationChunkHolder> holders = new Long2ObjectOpenHashMap<>(chunks.size());
        chunks.forEach((key, chunk) -> holders.put((long) key, new LodChunkHolder(chunk, ChunkStatus.FEATURES)));
        int side = size + 2;
        for (int i = 0; i < side * side; i++) {
            int index = reversed ? side * side - 1 - i : i;
            checkCancelled(cancelled);
            this.decorate(chunks.get(LodGenerator.pack(minX - 1 + index / side, minZ - 1 + index % side)), holders);
        }
        List<LodChunk> tile = new ArrayList<>(size * size);
        for (int x = minX; x < minX + size; x++) {
            for (int z = minZ; z < minZ + size; z++) {
                tile.add(this.toLodChunk(chunks.get(LodGenerator.pack(x, z))));
            }
        }
        return tile;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    // Like vanilla's features step, through a real WorldGenRegion over the chunk and its neighbours
    private void decorate(ProtoChunk chunk, Long2ObjectOpenHashMap<GenerationChunkHolder> holders) {
        ChunkPos pos = chunk.getPos();
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
        // Features can only reach the direct neighbours, WorldGenRegion refuses anything further away
        StaticCache2D<GenerationChunkHolder> neighbours = StaticCache2D.create(pos.x(), pos.z(), FEATURES_STEP.directDependencies().getRadius(),
                (x, z) -> holders.get(LodGenerator.pack(x, z)));
        WorldGenRegion region = new WorldGenRegion(this.level, neighbours, FEATURES_STEP, chunk);
        this.generator.applyBiomeDecoration(region, chunk, this.noStructures);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
    }

    private ProtoChunk generateTerrain(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);

        // Like vanilla, mark each finished step: the chunk refuses e.g. biome lookups before its biomes exist
        this.generator.createBiomes(this.randomState, Blender.empty(), this.noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        this.generator.fillFromNoise(Blender.empty(), this.randomState, this.noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        this.generator.buildSurface(chunk, this.context, this.randomState, this.noStructures, this.biomeManager(chunk), Blender.empty(), this.possibleBiomes);
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        return chunk;
    }

    // Biome blending at the chunk edge looks at the neighbouring chunks: those biomes are computed directly
    private BiomeManager biomeManager(ProtoChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return new BiomeManager((quartX, quartY, quartZ) -> {
            if (QuartPos.toSection(quartX) == pos.x() && QuartPos.toSection(quartZ) == pos.z()) {
                return chunk.getNoiseBiome(quartX, quartY, quartZ);
            }
            return this.biomeSource.getNoiseBiome(quartX, quartY, quartZ, this.randomState.sampler());
        }, this.biomeZoomSeed);
    }

    /**
     * Voxy shades LODs with the light stored next to each block, and without light data everything is black.
     * Instead of running the light engine, sky light is followed straight down each column with vanilla's rule:
     * blocks that let sky light through (air, plants) keep it, others take away their light dampening, at least one
     * (leaves and water one level per block, solid blocks all of it). Light spreading sideways is left out.
     */
    private LodChunk toLodChunk(ProtoChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        int minY = chunk.getMinY();
        int height = sections.length * 16;

        // Sky light per column and height; columns end where it reaches 0
        byte[] light = new byte[256 * height];
        int highest = minY - 1; // highest non-air block
        for (int column = 0; column < 256; column++) {
            int x = column & 15;
            int z = column >> 4;
            int level = MAX_SKY_LIGHT;
            for (int y = height - 1; y >= 0 && level > 0; y--) {
                LevelChunkSection section = sections[y >> 4];
                if (section.hasOnlyAir()) {
                    // keep the light through the whole empty section
                    int bottom = y & ~15;
                    for (; y >= bottom; y--) {
                        light[column * height + y] = (byte) level;
                    }
                    y = bottom;
                    continue;
                }
                BlockState state = section.getBlockState(x, y & 15, z);
                if (!state.isAir()) {
                    highest = Math.max(highest, minY + y);
                    if (!state.propagatesSkylightDown()) {
                        level = Math.max(0, level - Math.max(1, state.getLightDampening()));
                    }
                }
                light[column * height + y] = (byte) level;
            }
        }

        // Everything well above the terrain is empty sky: no need to send it
        int count = Math.min(sections.length, ((highest + 1) >> 4) - minSectionY + 1);
        LevelChunkSection[] kept = new LevelChunkSection[count];
        DataLayer[] skyLight = new DataLayer[count];
        for (int index = 0; index < count; index++) {
            kept[index] = sections[index];
            skyLight[index] = (minSectionY + index) << 4 > highest ? LodChunk.FULL_SKY_LIGHT : skyLight(light, height, index);
        }
        ChunkPos pos = chunk.getPos();
        return new LodChunk(pos.x(), pos.z(), minSectionY, kept, skyLight);
    }

    private static @Nullable DataLayer skyLight(byte[] light, int height, int sectionIndex) {
        DataLayer layer = null;
        for (int column = 0; column < 256; column++) {
            for (int y = 0; y < 16; y++) {
                int level = light[column * height + sectionIndex * 16 + y];
                if (level == 0) {
                    continue;
                }
                if (layer == null) {
                    layer = new DataLayer();
                }
                layer.set(column & 15, y, column >> 4, level);
            }
        }
        return layer;
    }
}
