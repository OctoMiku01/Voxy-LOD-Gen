package io.github.octomiku01.lodgen.client;

import io.github.octomiku01.lodgen.LodChunk;
import io.github.octomiku01.lodgen.LodChunkPayload;
import java.util.ArrayDeque;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Hands LODs received from the server to Voxy. LODs that arrive before Voxy is ready (e.g. right after joining) are
 * kept and handed over later: the server counts them as delivered and would not send them again.
 * Only loaded when Voxy is installed; used on the client thread only.
 */
final class ClientLodReceiver {
    private static final int MAX_PENDING = 4096;
    private static final int RETRIES_PER_TICK = 64;

    private static final ArrayDeque<LodChunkPayload> PENDING = new ArrayDeque<>();

    private ClientLodReceiver() {
    }

    static void receive(LodChunkPayload payload, Minecraft client) {
        if (!PENDING.isEmpty() || !ingest(payload, client)) {
            if (PENDING.size() >= MAX_PENDING) {
                PENDING.pollFirst();
            }
            PENDING.addLast(payload);
        }
    }

    static void tick(Minecraft client) {
        for (int i = 0; i < RETRIES_PER_TICK && !PENDING.isEmpty(); i++) {
            if (!ingest(PENDING.peekFirst(), client)) {
                return;
            }
            PENDING.pollFirst();
        }
    }

    static void clear() {
        PENDING.clear();
    }

    /**
     * @return false if Voxy cannot take it yet; LODs for another dimension than the current one are dropped
     */
    private static boolean ingest(LodChunkPayload payload, Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return false;
        }
        if (!level.dimension().equals(payload.dimension())) {
            return true;
        }
        WorldIdentifier world = WorldIdentifier.of(level);
        if (world == null) {
            return false;
        }
        boolean[] accepted = {true};
        LodChunk.decode(payload.data(), level.palettedContainerFactory(), (sectionY, section, skyLight, blockLight) -> {
            if (accepted[0] && !VoxelIngestService.rawIngest(world, section, payload.x(), sectionY, payload.z(), blockLight, skyLight)) {
                accepted[0] = false;
            }
        });
        return accepted[0];
    }
}
