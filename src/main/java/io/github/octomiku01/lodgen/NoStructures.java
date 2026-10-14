package io.github.octomiku01.lodgen;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * A structure manager for a world without structures.
 * <p>
 * The vanilla one looks up structure references through {@code level.getChunk(...)}, which would load or generate
 * real chunks around every LOD chunk (noise generation asks it for terrain adaptation around structures). The far
 * LOD tier generates no structures, so every lookup answers "nothing here" without touching the world.
 */
final class NoStructures extends StructureManager {
    NoStructures(ServerLevel level) {
        super(level, level.getServer().getWorldGenSettings().options(), null);
    }

    @Override
    public StructureManager forWorldGenRegion(WorldGenRegion region) {
        return this;
    }

    @Override
    public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
        return List.of();
    }

    @Override
    public List<StructureStart> startsForStructure(SectionPos pos, Structure structure) {
        return List.of();
    }

    @Override
    public void fillStartsForStructure(Structure structure, LongSet references, Consumer<StructureStart> consumer) {
    }

    @Override
    public StructureStart getStartForStructure(SectionPos pos, Structure structure, StructureAccess chunk) {
        return null;
    }

    @Override
    public void setStartForStructure(SectionPos pos, Structure structure, StructureStart start, StructureAccess chunk) {
    }

    @Override
    public void addReferenceForStructure(SectionPos pos, Structure structure, long reference, StructureAccess chunk) {
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public StructureStart getStructureAt(BlockPos pos, Structure structure) {
        return StructureStart.INVALID_START;
    }
}
