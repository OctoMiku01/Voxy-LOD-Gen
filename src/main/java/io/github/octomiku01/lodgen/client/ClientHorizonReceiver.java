package io.github.octomiku01.lodgen.client;

import io.github.octomiku01.lodgen.LodChunk;
import io.github.octomiku01.lodgen.LodGen;
import io.github.octomiku01.lodgen.LodHorizonBuilder;
import io.github.octomiku01.lodgen.LodHorizonPayload;
import io.github.octomiku01.lodgen.VoxySink;
import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the horizon the server sends as samples (see {@link LodHorizonPayload}) into rough LODs and hands them to
 * Voxy, on a thread of its own: the render thread only takes the payloads in. Samples that arrive before Voxy is ready
 * are kept: the server counts them as delivered and would not send them again. Only loaded when Voxy is installed.
 */
final class ClientHorizonReceiver {
    /** Chunks waiting to be built, at most; the server sends a few hundred per second */
    private static final int MAX_QUEUED = 200_000;
    /** Rough LODs are built far faster than Voxy takes them in: above this many waiting sections, building waits */
    private static final int MAX_VOXY_BACKLOG = 10_000;

    private record Work(ClientLevel level, WorldIdentifier world, LodHorizonPayload.Chunk chunk) {
    }

    private static final LinkedBlockingQueue<Work> QUEUE = new LinkedBlockingQueue<>(MAX_QUEUED);
    /** Payloads that came before Voxy was ready; client thread only */
    private static final ArrayDeque<LodHorizonPayload> PENDING = new ArrayDeque<>();
    private static volatile int built;
    private static @Nullable Thread thread;

    private ClientHorizonReceiver() {
    }

    // Client thread
    static void receive(LodHorizonPayload payload, Minecraft client) {
        if (!PENDING.isEmpty() || !queue(payload, client)) {
            PENDING.addLast(payload);
        }
    }

    static void tick(Minecraft client) {
        while (!PENDING.isEmpty() && queue(PENDING.peekFirst(), client)) {
            PENDING.pollFirst();
        }
    }

    static void clear() {
        PENDING.clear();
        QUEUE.clear();
        built = 0;
    }

    static int built() {
        return built;
    }

    static int waiting() {
        return QUEUE.size();
    }

    /** @return false if Voxy is not ready yet; samples for another dimension than the current one are dropped */
    private static boolean queue(LodHorizonPayload payload, Minecraft client) {
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
        start();
        for (LodHorizonPayload.Chunk chunk : payload.chunks()) {
            if (!QUEUE.offer(new Work(level, world, chunk))) {
                // Far more than the server sends: something is stuck, the rest is dropped
                break;
            }
        }
        return true;
    }

    private static synchronized void start() {
        if (thread == null) {
            thread = new Thread(ClientHorizonReceiver::run, "LodGen-Horizon-Client");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
    }

    private static void run() {
        @Nullable ClientLevel builderLevel = null;
        @Nullable LodHorizonBuilder builder = null;
        try {
            while (true) {
                Work work = QUEUE.take();
                while (VoxySink.backlog() > MAX_VOXY_BACKLOG) {
                    Thread.sleep(50);
                }
                if (work.level != Minecraft.getInstance().level) {
                    // Left the world or changed dimension
                    continue;
                }
                if (work.level != builderLevel) {
                    builderLevel = work.level;
                    builder = new LodHorizonBuilder(work.level);
                }
                try {
                    LodHorizonPayload.Chunk chunk = work.chunk;
                    LodChunk lod = builder.build(chunk.x(), chunk.z(), chunk.samples());
                    lod.forEachSection((sectionY, section, skyLight, blockLight) ->
                            VoxelIngestService.rawIngest(work.world, section, chunk.x(), sectionY, chunk.z(), blockLight, skyLight));
                    built++;
                } catch (RuntimeException e) {
                    LodGen.LOGGER.warn("Could not build the horizon at chunk {}, {}", work.chunk.x(), work.chunk.z(), e);
                }
            }
        } catch (InterruptedException e) {
            // Never interrupted; a daemon thread ends with the game
        }
    }
}
