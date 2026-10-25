package io.github.octomiku01.lodgen;

import io.github.octomiku01.lodgen.mixin.ChunkMapInvoker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;

/**
 * Generates the terrain of one chunk for an LOD and hands it to Voxy.
 * <p>
 * Only the steps that shape what an LOD shows run here: biomes, noise (base terrain), surface, carvers (caves and
 * ravines, so their openings show from afar) and, for closer LODs,
 * features and structures, then light. Mob spawning and saving are skipped. The chunk never enters the vanilla chunk
 * system, so nothing is loaded, ticked or written to the region files.
 */
final class LodChunkBuilder {
    /** Enough for the edges of the tiles around the one being built, a few hundred MB at most */
    private static final int MAX_EDGE_TERRAIN = 4096;
    private static final ChunkStep CARVERS_STEP = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.CARVERS);
    private static final ChunkStep FEATURES_STEP = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final StructureManager structures;
    private final WorldGenerationContext context;
    private final long biomeZoomSeed;
    private final CarverSource carverSource;
    private final boolean keepUnderground;
    /**
     * Terrain of the chunks along tile edges, before features: neighbouring tiles need the same chunks around their
     * edge, and terrain is by far the slowest step. Copies, because features change the chunks they run on.
     */
    private final Map<Long, LevelChunkSection[]> edgeTerrain = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LevelChunkSection[]> eldest) {
            return this.size() > MAX_EDGE_TERRAIN;
        }
    });

    private LodChunkBuilder(ServerLevel level, ChunkGenerator generator, boolean structures, boolean keepUnderground) {
        this.level = level;
        this.generator = generator;
        this.biomeSource = generator.getBiomeSource();
        this.randomState = level.getChunkSource().randomState();
        this.structures = structures ? new LodStructures(level) : new NoStructures(level);
        this.context = new WorldGenerationContext(generator, level);
        this.biomeZoomSeed = BiomeManager.obfuscateSeed(level.getSeed());
        this.carverSource = new CarverSource(level);
        this.keepUnderground = keepUnderground;
    }

    /**
     * Any generator: the vanilla noise based one (also used by most worldgen mods), superflat, or one of a mod. A mod's
     * own generator may expect the real chunk system and fail here, see {@link #noiseBased()}.
     *
     * @param structures whether LODs include structures (villages, towers...); they also shape the terrain around them
     * @param keepUnderground also keep the sections deep underground that no daylight reaches
     */
    static LodChunkBuilder create(ServerLevel level, boolean structures, boolean keepUnderground) {
        return new LodChunkBuilder(level, level.getChunkSource().getGenerator(), structures, keepUnderground);
    }

    /** The vanilla noise based generator, known to work; others are tried and given up if they only fail */
    boolean noiseBased() {
        return this.generator instanceof NoiseBasedChunkGenerator;
    }

    /** Its name for the log, e.g. minecraft:flat */
    String generatorName() {
        return this.generator.getTypeNameForDataFixer().map(Object::toString).orElse(this.generator.getClass().getName());
    }

    /**
     * The LOD of a real chunk, read from the save: with everything players built. The chunk is not loaded into the
     * world; light is computed like for generated chunks.
     *
     * @return null if the chunk is not saved (any more)
     */
    @Nullable LodChunk fromSaved(ChunkPos pos) {
        CompoundTag tag = ((ChunkMapInvoker) this.level.getChunkSource().chunkMap).lodgen$readChunk(pos).join().orElse(null);
        if (tag == null) {
            return null;
        }
        SerializableChunkData data = SerializableChunkData.parse(this.level, this.level.palettedContainerFactory(), tag);
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
        LevelChunkSection[] sections = chunk.getSections();
        for (SerializableChunkData.SectionData section : data.sectionData()) {
            int index = chunk.getSectionIndexFromSectionY(section.y());
            if (section.chunkSection() != null && index >= 0 && index < sections.length) {
                sections[index] = section.chunkSection();
            }
        }
        Long2ObjectOpenHashMap<ProtoChunk> chunks = new Long2ObjectOpenHashMap<>();
        chunks.put(LodGenerator.pack(pos.x(), pos.z()), chunk);
        // Up to the top of the world: what was built and torn down again must disappear from the LOD too
        return this.toLodChunk(chunk, LodLight.light(this.level, chunks, pos.x(), pos.z(), 1), true);
    }

    /** Terrain only: what distant LODs show */
    LodChunk build(ChunkPos pos) {
        Long2ObjectOpenHashMap<ProtoChunk> chunks = new Long2ObjectOpenHashMap<>();
        ProtoChunk chunk = this.generateBiomes(pos);
        this.generateTerrain(chunk, possibleBiomes(chunk));
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
        // Biomes first, one ring further out than the terrain: the surface rules of each chunk need its neighbours'
        // biomes, see possibleBiomes. They cost little next to the terrain
        Long2ObjectOpenHashMap<ProtoChunk> biomes = new Long2ObjectOpenHashMap<>();
        for (int x = minX - 3; x < minX + size + 3; x++) {
            for (int z = minZ - 3; z < minZ + size + 3; z++) {
                checkCancelled(cancelled);
                long key = LodGenerator.pack(x, z);
                boolean outer = x < minX - 2 || x >= minX + size + 2 || z < minZ - 2 || z >= minZ + size + 2;
                LevelChunkSection[] cached = outer ? null : this.edgeTerrain.get(key);
                biomes.put(key, cached != null ? this.restoreTerrain(new ChunkPos(x, z), cached) : this.generateBiomes(new ChunkPos(x, z)));
            }
        }
        Long2ObjectOpenHashMap<ProtoChunk> chunks = new Long2ObjectOpenHashMap<>();
        for (int x = minX - 2; x < minX + size + 2; x++) {
            for (int z = minZ - 2; z < minZ + size + 2; z++) {
                checkCancelled(cancelled);
                long key = LodGenerator.pack(x, z);
                ProtoChunk chunk = biomes.get(key);
                // Chunks restored from the edge cache already have their terrain
                if (chunk.getPersistedStatus() == ChunkStatus.BIOMES) {
                    this.generateTerrain(chunk, possibleBiomes(biomes, x, z));
                    if (x < minX + 2 || x >= minX + size - 2 || z < minZ + 2 || z >= minZ + size - 2) {
                        this.edgeTerrain.put(key, copy(chunk.getSections()));
                    }
                }
                chunks.put(key, chunk);
            }
        }
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
        this.generator.applyBiomeDecoration(region, chunk, this.structures);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
    }

    private static LevelChunkSection[] copy(LevelChunkSection[] sections) {
        LevelChunkSection[] copy = new LevelChunkSection[sections.length];
        for (int i = 0; i < sections.length; i++) {
            copy[i] = copy(sections[i]);
        }
        return copy;
    }

    private static LevelChunkSection copy(LevelChunkSection section) {
        LevelChunkSection copy = section.copy();
        // Mods keep their own data per section (Lithium: which blocks tick randomly) and only set it up when a section
        // is created normally or recounted, not when it is copied; without it, placing blocks crashes
        copy.recalcBlockCounts();
        return copy;
    }

    // A chunk at the same point as right after generateTerrain
    private ProtoChunk restoreTerrain(ChunkPos pos, LevelChunkSection[] terrain) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            sections[i] = copy(terrain[i]);
        }
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        return chunk;
    }

    private ProtoChunk generateBiomes(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
        this.generator.createBiomes(this.randomState, Blender.empty(), this.structures, chunk).join();
        // Like vanilla, mark each finished step: the chunk refuses e.g. biome lookups before its biomes exist
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        return chunk;
    }

    /** @param possibleBiomes see {@link #possibleBiomes(ProtoChunk)} */
    private void generateTerrain(ProtoChunk chunk, Set<Holder<Biome>> possibleBiomes) {
        this.generator.fillFromNoise(Blender.empty(), this.randomState, this.structures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        BiomeManager biomes = this.biomeManager(chunk);
        this.generator.buildSurface(chunk, this.context, this.randomState, this.structures, biomes, Blender.empty(), possibleBiomes);
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        this.carve(chunk, biomes);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
    }

    /**
     * The biomes surface rules may meet in this chunk: they turn every rule about other biomes into a constant instead
     * of looking up the biome for each block, which made up half the time of a chunk. Vanilla also counts the
     * neighbouring chunks, whose biomes blend in along the edge; for a single chunk they are left out, as computing
     * them costs more than it saves. Near a biome border a strip of a few blocks may then get the other biome's top
     * block (grass instead of sand), which does not show in distant LODs.
     */
    private static Set<Holder<Biome>> possibleBiomes(ProtoChunk chunk) {
        Set<Holder<Biome>> biomes = new HashSet<>();
        addBiomes(chunk, biomes);
        return biomes;
    }

    /** Like vanilla: the biomes of the chunk and the 8 around it, which all have their biomes in {@code chunks} */
    private static Set<Holder<Biome>> possibleBiomes(Long2ObjectOpenHashMap<ProtoChunk> chunks, int x, int z) {
        Set<Holder<Biome>> biomes = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                addBiomes(chunks.get(LodGenerator.pack(x + dx, z + dz)), biomes);
            }
        }
        return biomes;
    }

    private static void addBiomes(ProtoChunk chunk, Set<Holder<Biome>> biomes) {
        for (LevelChunkSection section : chunk.getSections()) {
            section.getBiomes().forEachInPalette(biomes::add);
        }
    }

    /**
     * Like vanilla's carvers step. Caves and ravines start up to 8 chunks away, and the carvers only ask those chunks
     * for their biome: {@link CarverRegion} answers that without needing the chunks themselves. They only write to
     * this chunk.
     */
    private void carve(ProtoChunk chunk, BiomeManager biomes) {
        ChunkPos pos = chunk.getPos();
        StaticCache2D<GenerationChunkHolder> self = StaticCache2D.create(pos.x(), pos.z(), 0, (x, z) -> new LodChunkHolder(chunk, ChunkStatus.SURFACE));
        WorldGenRegion region = new CarverRegion(this.level, self, chunk, this.carverSource);
        this.generator.applyCarvers(region, this.level.getSeed(), this.randomState, biomes, this.structures, chunk);
    }

    private static final class CarverRegion extends WorldGenRegion {
        private final ProtoChunk center;
        private final CarverSource source;

        CarverRegion(ServerLevel level, StaticCache2D<GenerationChunkHolder> cache, ProtoChunk center, CarverSource source) {
            super(level, cache, CARVERS_STEP, center);
            this.center = center;
            this.source = source;
        }

        @Override
        public ChunkAccess getChunk(int x, int z) {
            ChunkPos pos = this.center.getPos();
            if (x == pos.x() && z == pos.z()) {
                return this.center;
            }
            // The carvers ask it for its biome right away
            this.source.asking.set(LodGenerator.pack(x, z));
            return this.source;
        }
    }

    /**
     * Stands in for the chunks where caves start, for all workers: knows their carver biome. Each chunk asks the 17 by
     * 17 chunks around it, so it is remembered.
     */
    private static final class CarverSource extends ProtoChunk {
        /** Which chunk the carvers on this thread asked for, see {@link CarverRegion#getChunk} */
        final ThreadLocal<Long> asking = new ThreadLocal<>();
        private final ChunkCache<BiomeGenerationSettings> biomes = new ChunkCache<>(65536);

        CarverSource(ServerLevel level) {
            super(ChunkPos.ZERO, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        }

        @Override
        public BiomeGenerationSettings carverBiome(Supplier<BiomeGenerationSettings> supplier) {
            Long chunk = this.asking.get();
            return chunk == null ? supplier.get() : this.biomes.get(chunk, key -> supplier.get());
        }
    }

    /**
     * Biome blending at the chunk edge looks at the neighbouring chunks: those biomes are computed directly, and
     * remembered, since every block along the edge asks again and computing one costs a lot. Only for this chunk, on
     * one thread.
     */
    private BiomeManager biomeManager(ProtoChunk chunk) {
        ChunkPos pos = chunk.getPos();
        Long2ObjectOpenHashMap<Holder<Biome>> neighbours = new Long2ObjectOpenHashMap<>();
        return new BiomeManager((quartX, quartY, quartZ) -> {
            if (QuartPos.toSection(quartX) == pos.x() && QuartPos.toSection(quartZ) == pos.z()) {
                return chunk.getNoiseBiome(quartX, quartY, quartZ);
            }
            long key = BlockPos.asLong(quartX, quartY, quartZ);
            Holder<Biome> biome = neighbours.get(key);
            if (biome == null) {
                biome = this.biomeSource.getNoiseBiome(quartX, quartY, quartZ, this.randomState.sampler());
                neighbours.put(key, biome);
            }
            return biome;
        }, this.biomeZoomSeed);
    }

    /**
     * Keeps the sections from the bottom of the world up to just above the terrain, with their light (see
     * {@link LodLight}): Voxy shades LODs with it, and without light data everything is black.
     */
    private LodChunk toLodChunk(ProtoChunk chunk, LodLight light) {
        return this.toLodChunk(chunk, light, false);
    }

    /**
     * @param toTop also send the empty sky above the highest blocks; needed where the chunk may have had more blocks
     *              before (a real chunk sent again after a change), so the old ones get cleared
     */
    private LodChunk toLodChunk(ProtoChunk chunk, LodLight light, boolean toTop) {
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
        int end = toTop ? sections.length : Math.min(sections.length, highest + 2);
        ChunkPos pos = chunk.getPos();
        // Deep underground, out of sight from the sky (see LodLight.findVisible), only shows from inside caves: no
        // need to send it either.
        // The neighbours count too where they are known, a ravine next door shows this chunk's side; one more section
        // stays as the floor.
        int start = 0;
        if (!this.keepUnderground) {
            int lowest = light.lowestVisibleSection(pos.x(), pos.z());
            lowest = Math.min(lowest, light.lowestVisibleSection(pos.x() - 1, pos.z()));
            lowest = Math.min(lowest, light.lowestVisibleSection(pos.x() + 1, pos.z()));
            lowest = Math.min(lowest, light.lowestVisibleSection(pos.x(), pos.z() - 1));
            lowest = Math.min(lowest, light.lowestVisibleSection(pos.x(), pos.z() + 1));
            start = Math.clamp(lowest - 1 - minSectionY, 0, Math.max(0, end - 1));
        }
        int count = end - start;
        LevelChunkSection[] kept = new LevelChunkSection[count];
        DataLayer[] skyLight = new DataLayer[count];
        DataLayer[] blockLight = new DataLayer[count];
        for (int i = 0; i < count; i++) {
            int sectionY = minSectionY + start + i;
            kept[i] = sections[start + i];
            skyLight[i] = light.skyLight(pos, sectionY);
            blockLight[i] = light.blockLight(pos, sectionY);
        }
        return new LodChunk(pos.x(), pos.z(), minSectionY + start, kept, skyLight, blockLight);
    }
}
