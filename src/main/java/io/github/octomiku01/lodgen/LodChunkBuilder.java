package io.github.octomiku01.lodgen;

import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;

/**
 * Generates the terrain of one chunk for an LOD and hands it to Voxy.
 * <p>
 * Only the steps that shape what a distant LOD shows run here: biomes, noise (base terrain) and surface. Structures,
 * carvers, features, the light engine, mob spawning and saving are skipped. The chunk never enters the vanilla chunk
 * system, so nothing is loaded, ticked or written to the region files. The result goes to a {@link LodSink}.
 */
final class LodChunkBuilder {
    private static final int FULL_SKY_LIGHT = 15;

    private final ServerLevel level;
    private final NoiseBasedChunkGenerator generator;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final StructureManager noStructures;
    private final WorldGenerationContext context;
    private final Set<Holder<Biome>> possibleBiomes;
    private final long biomeZoomSeed;
    private final LodSink sink;

    private LodChunkBuilder(ServerLevel level, NoiseBasedChunkGenerator generator, LodSink sink) {
        this.level = level;
        this.generator = generator;
        this.biomeSource = generator.getBiomeSource();
        this.randomState = level.getChunkSource().randomState();
        this.noStructures = new NoStructures(level);
        this.context = new WorldGenerationContext(generator, level);
        // Surface rules only use this to skip rules for biomes that cannot occur; a superset is always correct
        this.possibleBiomes = this.biomeSource.possibleBiomes();
        this.biomeZoomSeed = BiomeManager.obfuscateSeed(level.getSeed());
        this.sink = sink;
    }

    /**
     * @return null if this dimension cannot be generated (not a noise based generator, e.g. superflat)
     */
    static @Nullable LodChunkBuilder create(ServerLevel level, LodSink sink) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return null;
        }
        return new LodChunkBuilder(level, generator, sink);
    }

    /**
     * @return false if the sink did not accept the data (e.g. Voxy not ready yet), so the chunk should be tried again later
     */
    boolean build(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);

        // Like vanilla, mark each finished step: the chunk refuses e.g. biome lookups before its biomes exist
        this.generator.createBiomes(this.randomState, Blender.empty(), this.noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        this.generator.fillFromNoise(Blender.empty(), this.randomState, this.noStructures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        this.generator.buildSurface(chunk, this.context, this.randomState, this.noStructures, this.biomeManager(chunk), Blender.empty(), this.possibleBiomes);
        chunk.setPersistedStatus(ChunkStatus.SURFACE);

        return this.ingest(chunk);
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
     * Instead of running the light engine, sky light is approximated per column: full above the terrain, fading by
     * one level per block of water below it, and dark below the first other block.
     */
    private boolean ingest(ProtoChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        int minY = chunk.getMinY();
        int maxY = minY + sections.length * 16 - 1;

        // Per column: sky light ends below this y, and the light it has at each y above that is known
        int[] skyBottom = new int[256];
        int[] skyTop = new int[256]; // first non-air block from the top, everything above has full sky light
        int highest = minY - 1;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = z << 4 | x;
                int top = minY - 1;
                int bottom = minY;
                int y = maxY;
                // find the first non-air block
                for (; y >= minY; y--) {
                    LevelChunkSection section = sections[(y >> 4) - minSectionY];
                    if (section.hasOnlyAir()) {
                        y = (y & ~15);
                        continue;
                    }
                    if (!section.getBlockState(x, y & 15, z).isAir()) {
                        top = y;
                        break;
                    }
                }
                // light fades through water below the surface
                int light = FULL_SKY_LIGHT;
                for (; y >= minY; y--) {
                    BlockState state = sections[(y >> 4) - minSectionY].getBlockState(x, y & 15, z);
                    if (state.getFluidState().isEmpty() || --light <= 0) {
                        break;
                    }
                }
                bottom = y + 1;
                skyTop[column] = top;
                skyBottom[column] = bottom;
                highest = Math.max(highest, top);
            }
        }

        // Everything well above the terrain is empty sky: no need to send it
        int lastSection = Math.min(sections.length - 1, ((highest + 1) >> 4) - minSectionY);
        ChunkPos pos = chunk.getPos();
        for (int index = 0; index <= lastSection; index++) {
            int sectionY = minSectionY + index;
            DataLayer skyLight = this.skyLight(sectionY << 4, skyTop, skyBottom);
            if (!this.sink.accept(pos.x(), sectionY, pos.z(), sections[index], skyLight)) {
                return false;
            }
        }
        return true;
    }

    private @Nullable DataLayer skyLight(int baseY, int[] skyTop, int[] skyBottom) {
        DataLayer layer = null;
        for (int column = 0; column < 256; column++) {
            int from = Math.max(baseY, skyBottom[column]);
            for (int y = from; y < baseY + 16; y++) {
                int light = y > skyTop[column] ? FULL_SKY_LIGHT : FULL_SKY_LIGHT - (skyTop[column] - y + 1);
                if (light <= 0) {
                    continue;
                }
                if (layer == null) {
                    layer = new DataLayer();
                }
                layer.set(column & 15, y - baseY, column >> 4, light);
            }
        }
        return layer;
    }
}
