package io.github.octomiku01.lodgen;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: where the other players in the dimension are, every second while the 3D map of this player is
 * open, also those too far away to be shown on the LODs.
 */
public record MapPlayersPayload(ResourceKey<Level> dimension, List<Entry> players) implements CustomPacketPayload {
    public static final Type<MapPlayersPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "map_players"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapPlayersPayload> CODEC = StreamCodec.ofMember(MapPlayersPayload::write, MapPlayersPayload::read);

    public record Entry(UUID uuid, String name, double x, double y, double z) {
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeVarInt(this.players.size());
        for (Entry entry : this.players) {
            UUIDUtil.STREAM_CODEC.encode(buf, entry.uuid);
            buf.writeUtf(entry.name, 64);
            buf.writeDouble(entry.x);
            buf.writeDouble(entry.y);
            buf.writeDouble(entry.z);
        }
    }

    private static MapPlayersPayload read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        int count = buf.readVarInt();
        Entry[] players = new Entry[count];
        for (int i = 0; i < count; i++) {
            players[i] = new Entry(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        return new MapPlayersPayload(dimension, List.of(players));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
