package io.github.octomiku01.lodgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: how far the LODs of this player are around them, for the 3D map's progress view; sent every few
 * seconds while the client asks for it ({@link Request}). A square of {@code size} x {@code size} cells of
 * {@code cellSize} chunks each, row by row from its north-west corner at chunk {@code minX}, {@code minZ}; each cell
 * holds one of the STATE_ values.
 */
public record MapProgressPayload(ResourceKey<Level> dimension, int minX, int minZ, int cellSize, int size, byte[] cells)
        implements CustomPacketPayload {
    public static final byte STATE_NONE = 0;
    public static final byte STATE_HORIZON = 1;
    public static final byte STATE_TERRAIN = 2;
    public static final byte STATE_DETAILS = 3;
    public static final byte STATE_EXPLORED = 4;

    public static final Type<MapProgressPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "map_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapProgressPayload> CODEC = StreamCodec.ofMember(MapProgressPayload::write,
            MapProgressPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeVarInt(this.minX);
        buf.writeVarInt(this.minZ);
        buf.writeVarInt(this.cellSize);
        buf.writeVarInt(this.size);
        buf.writeByteArray(this.cells);
    }

    private static MapProgressPayload read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        int minX = buf.readVarInt();
        int minZ = buf.readVarInt();
        int cellSize = buf.readVarInt();
        int size = buf.readVarInt();
        byte[] cells = buf.readByteArray(size * size);
        return new MapProgressPayload(dimension, minX, minZ, cellSize, size, cells);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client to server: the progress view is open, send it (again) */
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "map_progress_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
