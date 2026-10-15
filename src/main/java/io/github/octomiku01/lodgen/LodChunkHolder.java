package io.github.octomiku01.lodgen;

import io.github.octomiku01.lodgen.mixin.GenerationChunkHolderAccessor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Presents one of our chunks to vanilla's {@link net.minecraft.server.level.WorldGenRegion} the way the chunk system
 * would: as complete up to {@code status}. Never part of the real chunk system, so it is never scheduled or saved.
 */
final class LodChunkHolder extends GenerationChunkHolder {
    LodChunkHolder(ChunkAccess chunk, ChunkStatus status) {
        super(chunk.getPos());
        AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> futures = ((GenerationChunkHolderAccessor) (Object) this).lodgen$futures();
        CompletableFuture<ChunkResult<ChunkAccess>> done = CompletableFuture.completedFuture(ChunkResult.of(chunk));
        for (int index = 0; index <= status.getIndex(); index++) {
            futures.set(index, done);
        }
    }

    @Override
    protected void addSaveDependency(CompletableFuture<?> future) {
    }

    @Override
    public int getTicketLevel() {
        return 0;
    }

    @Override
    public int getQueueLevel() {
        return 0;
    }
}
