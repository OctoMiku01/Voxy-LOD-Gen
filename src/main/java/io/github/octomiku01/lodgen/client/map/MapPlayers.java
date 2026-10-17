package io.github.octomiku01.lodgen.client.map;

import io.github.octomiku01.lodgen.MapPlayersPayload;
import java.util.List;
import net.minecraft.client.Minecraft;

/** The players the server last said are in the dimension, for the 3D map; render thread only */
public final class MapPlayers {
    /** Older than this, the list is not shown anymore: the map was closed, or the server stopped sending */
    private static final long MAX_AGE_MILLIS = 3_000;

    private static MapPlayersPayload last;
    private static long receivedAt;

    private MapPlayers() {
    }

    public static void receive(MapPlayersPayload payload) {
        last = payload;
        receivedAt = System.currentTimeMillis();
    }

    static List<MapPlayersPayload.Entry> current(Minecraft minecraft) {
        if (last == null || minecraft.level == null || !last.dimension().equals(minecraft.level.dimension())
                || System.currentTimeMillis() - receivedAt > MAX_AGE_MILLIS) {
            return List.of();
        }
        return last.players();
    }

    public static void clear() {
        last = null;
    }
}
