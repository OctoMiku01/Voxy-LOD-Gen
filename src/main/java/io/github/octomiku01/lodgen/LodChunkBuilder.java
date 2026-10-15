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
 * features, then light. Structures, carvers, mob spawning and saving are skipped. The chunk never enters the vanilla chunk
 * system, so nothing is loaded, ticked or written to the region files.
 */
final class LodChunkBuilder {
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
        Long2ObjectOpenHashMap<ProtoChunk> chunks = new Long2ObjectOpenHashMap<>();
        ProtoChunk chunk = this.generateTerrain(pos);
        chunks.put(LodGenerator.pack(pos.x(), pos.z()), chunk);
        return this.toLodChunk(chunk, LodLight.light(this.level, chunks, pos.x(), pos.z(), 1));
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
        // Light reaches 15 blocks at most: the decorated ring is enough for the tile to be lit correctly
        checkCancelled(cancelled);
        LodLight light = LodLight.light(this.level, chunks, minX - 1, minZ - 1, side);
        List<LodChunk> tile = new ArrayList<>(size * size);
        for (int x = minX; x < minX + size; x++) {
            for (int z = minZ; z < minZ + size; z++) {
                tile.add(this.toLodChunk(chunks.get(LodGenerator.pack(x, z)), light));
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
     * Keeps the sections from the bottom of the world up to just above the terrain, with their light (see
     * {@link LodLight}): Voxy shades LODs with it, and without light data everything is black.
     */
    private LodChunk toLodChunk(ProtoChunk chunk, LodLight light) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        int highest = -1; // index of the highest section with blocks
        for (int index = sections.length - 1; index >= 0; index--) {
            if (!sections[index].hasOnlyAir()) {
                highest = index;
                break;
            }
        }
        // Everything above is empty sky: no need to send it
        int count = Math.min(sections.length, highest + 2);
        LevelChunkSection[] kept = new LevelChunkSection[count];
        DataLayer[] skyLight = new DataLayer[count];
        DataLayer[] blockLight = new DataLayer[count];
        ChunkPos pos = chunk.getPos();
        for (int index = 0; index < count; index++) {
            kept[index] = sections[index];
            skyLight[index] = light.skyLight(pos, minSectionY + index);
            blockLight[index] = light.blockLight(pos, minSectionY + index);
        }
        return new LodChunk(pos.x(), pos.z(), minSectionY, kept, skyLight, blockLight);
    }
}
