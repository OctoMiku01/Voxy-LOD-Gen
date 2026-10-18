package io.github.octomiku01.lodgen;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

/**
 * Structures (villages, towers, ruins...) for LODs, without the chunk system.
 * <p>
 * The vanilla structure manager finds structures through {@code level.getChunk(...)}, which would load or generate
 * real chunks. This one decides itself which structures start in a chunk, exactly like vanilla's structure starts
 * step, and keeps the result for the chunks around it. Noise generation uses it to flatten the terrain under
 * structures, and the features step to place their pieces.
 */
final class LodStructures extends StructureManager {
    /** Structures reach at most this many chunks from where they start, like in vanilla's references step */
    private static final int REFERENCE_RANGE = 8;
    /** How many chunks' structures are remembered, see {@link ChunkCache} */
    private static final int CACHED_CHUNKS = 131072;

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState state;
    private final ChunkCache<Map<Structure, StructureStart>> starts = new ChunkCache<>(CACHED_CHUNKS);
    private final ChunkCache<Map<Structure, List<StructureStart>>> references = new ChunkCache<>(CACHED_CHUNKS);

    LodStructures(ServerLevel level) {
        super(level, level.getServer().getWorldGenSettings().options(), null);
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.state = level.getChunkSource().getGeneratorState();
    }

    /** The structures that start in this chunk */
    private Map<Structure, StructureStart> startsIn(int chunkX, int chunkZ) {
        return this.starts.get(LodGenerator.pack(chunkX, chunkZ), key -> {
            // Like vanilla's structure starts step, on a chunk that only holds the result
            ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
            StructureHeights.begin();
            try {
                this.generator.createStructures(this.level.registryAccess(), this.state, this, chunk, this.level.getStructureManager(), this.level.dimension());
            } finally {
                StructureHeights.end();
            }
            return Map.copyOf(chunk.getAllStarts());
        });
    }

    /** The structures that reach into this chunk, like vanilla's references step */
    private Map<Structure, List<StructureStart>> reaching(int chunkX, int chunkZ) {
        return this.references.get(LodGenerator.pack(chunkX, chunkZ), key -> this.findReaching(chunkX, chunkZ));
    }

    private Map<Structure, List<StructureStart>> findReaching(int chunkX, int chunkZ) {
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        Map<Structure, List<StructureStart>> found = new HashMap<>();
        for (int x = chunkX - REFERENCE_RANGE; x <= chunkX + REFERENCE_RANGE; x++) {
            for (int z = chunkZ - REFERENCE_RANGE; z <= chunkZ + REFERENCE_RANGE; z++) {
                for (StructureStart start : this.startsIn(x, z).values()) {
                    if (start.isValid() && start.getBoundingBox().intersects(minBlockX, minBlockZ, minBlockX + 15, minBlockZ + 15)) {
                        found.computeIfAbsent(start.getStructure(), structure -> new ArrayList<>(1)).add(start);
                    }
                }
            }
        }
        return found;
    }

    @Override
    public StructureManager forWorldGenRegion(WorldGenRegion region) {
        return this;
    }

    @Override
    public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
        ImmutableList.Builder<StructureStart> result = ImmutableList.builder();
        this.reaching(pos.x(), pos.z()).forEach((structure, starts) -> {
            if (predicate.test(structure)) {
                result.addAll(starts);
            }
        });
        return result.build();
    }

    @Override
    public List<StructureStart> startsForStructure(SectionPos pos, Structure structure) {
        return this.reaching(pos.x(), pos.z()).getOrDefault(structure, List.of());
    }

    @Override
    public void fillStartsForStructure(Structure structure, LongSet references, Consumer<StructureStart> consumer) {
        for (long reference : references) {
            StructureStart start = this.startsIn(ChunkPos.getX(reference), ChunkPos.getZ(reference)).get(structure);
            if (start != null && start.isValid()) {
                consumer.accept(start);
            }
        }
    }

    @Override
    public @Nullable StructureStart getStartForStructure(SectionPos pos, Structure structure, StructureAccess chunk) {
        return chunk.getStartForStructure(structure);
    }

    @Override
    public boolean hasAnyStructureAt(BlockPos pos) {
        return !this.reaching(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())).isEmpty();
    }

    @Override
    public Map<Structure, LongSet> getAllStructuresAt(BlockPos pos) {
        Map<Structure, LongSet> result = new HashMap<>();
        this.reaching(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())).forEach((structure, starts) -> {
            LongSet set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            starts.forEach(start -> set.add(start.getChunkPos().pack()));
            result.put(structure, set);
        });
        return result;
    }

    @Override
    public void addReference(StructureStart start) {
        // Only used by /locate and treasure maps to remember visited structures; nothing to remember for LODs
    }
}
