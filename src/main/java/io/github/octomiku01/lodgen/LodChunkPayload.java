package io.github.octomiku01.lodgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: the LOD of one chunk, see {@link LodChunk#encoded()}. Only sent to clients that registered a
 * receiver for it, i.e. that have this mod and Voxy installed.
 */
public record LodChunkPayload(ResourceKey<Level> dimension, int x, int z, byte[] data) implements CustomPacketPayload {
    public static final Type<LodChunkPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LodChunkPayload> CODEC = StreamCodec.ofMember(LodChunkPayload::write, LodChunkPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeVarInt(this.x);
        buf.writeVarInt(this.z);
        buf.writeByteArray(this.data);
    }

    private static LodChunkPayload read(RegistryFriendlyByteBuf buf) {
        return new LodChunkPayload(buf.readResourceKey(Registries.DIMENSION), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
