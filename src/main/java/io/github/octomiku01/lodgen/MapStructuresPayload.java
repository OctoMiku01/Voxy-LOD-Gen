package io.github.octomiku01.lodgen;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: structures for the 3D map, while it is open; only those the player did not get yet, nearest to
 * where the map looks first. With {@code reset}, the client forgets those it had in that dimension first (the server
 * forgot what it sent, e.g. after a restart of the generation).
 */
public record MapStructuresPayload(ResourceKey<Level> dimension, boolean reset, List<StructureMarkers.Marker> markers) implements CustomPacketPayload {
    public static final Type<MapStructuresPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "map_structures"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapStructuresPayload> CODEC = StreamCodec.ofMember(MapStructuresPayload::write,
            MapStructuresPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeBoolean(this.reset);
        buf.writeVarInt(this.markers.size());
        for (StructureMarkers.Marker marker : this.markers) {
            buf.writeUtf(marker.id(), 256);
            buf.writeVarInt(marker.x());
            buf.writeVarInt(marker.y());
            buf.writeVarInt(marker.z());
        }
    }

    private static MapStructuresPayload read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        boolean reset = buf.readBoolean();
        int count = buf.readVarInt();
        StructureMarkers.Marker[] markers = new StructureMarkers.Marker[count];
        for (int i = 0; i < count; i++) {
            markers[i] = new StructureMarkers.Marker(buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
        return new MapStructuresPayload(dimension, reset, List.of(markers));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
