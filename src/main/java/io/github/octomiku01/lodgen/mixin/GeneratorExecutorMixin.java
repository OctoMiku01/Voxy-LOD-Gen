package io.github.octomiku01.lodgen.mixin;

import io.github.octomiku01.lodgen.LodWorkerThread;
import java.util.concurrent.Executor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Biomes and terrain of LODs are computed on the LOD worker that asks for them, not in vanilla's background pool:
 * that pool also generates the real chunks around the players, and the {@code threads} setting could not limit it.
 * Not required: if another mod replaces these methods, LODs only keep competing with the real chunks.
 */
@Mixin({ChunkGenerator.class, NoiseBasedChunkGenerator.class})
public abstract class GeneratorExecutorMixin {
    @ModifyArg(method = {"createBiomes", "fillFromNoise"}, require = 0, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private Executor lodgen$onLodWorker(Executor executor) {
        return LodWorkerThread.isCurrent() ? Runnable::run : executor;
    }
}
