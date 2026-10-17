package io.github.octomiku01.lodgen;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: where the 3D map of this player looks, in their current dimension, and how far around that it
 * shows (in chunks); a radius of 0 means the map was closed. Sent again every second while the map is open.
 */
public record MapFocusPayload(int chunkX, int chunkZ, int radius) implements CustomPacketPayload {
    public static final Type<MapFocusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "map_focus"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapFocusPayload> CODEC = StreamCodec.ofMember(MapFocusPayload::write, MapFocusPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(this.chunkX);
        buf.writeVarInt(this.chunkZ);
        buf.writeVarInt(this.radius);
    }

    private static MapFocusPayload read(RegistryFriendlyByteBuf buf) {
        return new MapFocusPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
