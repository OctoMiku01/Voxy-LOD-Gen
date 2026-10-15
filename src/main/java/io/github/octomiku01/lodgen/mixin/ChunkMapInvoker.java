package io.github.octomiku01.lodgen.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    /** Reads a saved chunk through the game's own storage, upgraded to the current version; nothing is loaded */
    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> lodgen$readChunk(ChunkPos pos);
}
