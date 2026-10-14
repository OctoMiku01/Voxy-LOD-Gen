package io.github.octomiku01.lodgen;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Sends LODs to a player whose client has this mod and Voxy installed. */
final class NetworkSink implements LodSink {
    private final ServerPlayer player;
    private final ResourceKey<Level> dimension;

    NetworkSink(ServerPlayer player, ResourceKey<Level> dimension) {
        this.player = player;
        this.dimension = dimension;
    }

    @Override
    public boolean accept(LodChunk chunk) {
        // The client only takes LODs for the dimension it is in; a left player gets them next time
        if (this.player.hasDisconnected() || this.player.level().dimension() != this.dimension) {
            return false;
        }
        ServerPlayNetworking.send(this.player, new LodChunkPayload(this.dimension, chunk.x, chunk.z, chunk.encoded()));
        return true;
    }
}
