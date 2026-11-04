package io.github.octomiku01.lodgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /lodgen pregen [radius]}: prepares what the server keeps for all players around a place (where the command
 * runs, the world spawn from the console), so they see it at once when they come: first the structures for the 3D map,
 * then the horizon's samples. Real LODs are still generated around each player, as they are per player.
 * <p>
 * Runs on half of the generation's threads at the lowest priority and waits while the generation is paused or the
 * server is slow. Both results are kept in the world, so after a restart the same command goes on where it stopped.
 */
final class LodPregen {
    private static final long REPORT_MILLIS = 30_000;
    private static volatile @Nullable LodPregen current;

    private final MinecraftServer server;
    private final CommandSourceStack source;
    private final LodGenerator generator;
    private final LodConfig config;
    private final ServerLevel level;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final BorderBounds border;
    private final int threads;
    private volatile boolean running = true;
    private volatile String phase = "structures";
    private final AtomicLong done = new AtomicLong();
    private volatile long total;
    private final long startedAt = System.currentTimeMillis();

    // The structure phase: regions still to do, nearest first; guarded by this
    private List<Long> regions = List.of();
    private int nextRegion;
    // The horizon phase: a walk through square rings around the center; guarded by this
    private int ring;
    private int ringIndex;

    private LodPregen(CommandSourceStack source, LodGenerator generator, LodConfig config, int radius) {
        this.server = source.getServer();
        this.source = source;
        this.generator = generator;
        this.config = config;
        this.level = source.getLevel();
        ChunkPos center = ChunkPos.containing(BlockPos.containing(source.getPosition()));
        this.centerX = center.x();
        this.centerZ = center.z();
        this.radius = radius;
        this.border = BorderBounds.of(this.level, config);
        this.threads = Math.max(1, config.workerThreads() / 2);
    }

    /** The radius the command uses without one: as far as the horizon reaches, else as far as the LODs */
    static int defaultRadius(LodConfig config) {
        return config.horizonRadius > config.radius ? config.horizonRadius : config.radius;
    }

    static Component start(CommandSourceStack source, @Nullable LodGenerator generator, LodConfig config, int radius) {
        if (generator == null) {
            return LodChat.error("Not running: LOD generation is turned off in the settings");
        }
        boolean structures = config.structures && config.mapStructures;
        if (!structures && generator.horizon() == null) {
            return LodChat.warning("Nothing to prepare: the horizon is off, and so are structures on the 3D map");
        }
        synchronized (LodPregen.class) {
            if (current != null) {
                return LodChat.warning("Already preparing, /lodgen pregen stop stops it");
            }
            LodPregen pregen = new LodPregen(source, generator, config, radius);
            current = pregen;
            new LodWorkerThread(pregen::run, "LodGen-Pregen").start();
            return LodChat.info(String.format("Preparing %d chunks around %d, %d in %s with %d threads: %s. Players see it at once when they come",
                    radius, pregen.centerX * 16 + 8, pregen.centerZ * 16 + 8, pregen.level.dimension().identifier(), pregen.threads,
                    structures && generator.horizon() != null ? "structures, then the horizon" : structures ? "structures" : "the horizon"));
        }
    }

    static Component stop() {
        LodPregen pregen = current;
        if (pregen == null) {
            return LodChat.warning("Nothing is being prepared");
        }
        pregen.running = false;
        return LodChat.success("Stopped; /lodgen pregen goes on from there");
    }

    /** When the server stops or the generation starts over */
    static void stopQuietly() {
        LodPregen pregen = current;
        if (pregen != null) {
            pregen.running = false;
        }
    }

    /** One line for /lodgen; null when nothing is being prepared */
    static @Nullable String status() {
        LodPregen pregen = current;
        return pregen == null ? null : pregen.progress();
    }

    private String progress() {
        long all = Math.max(1, this.total);
        return String.format("%s %d / %d (%d%%)", this.phase, this.done.get(), this.total, this.done.get() * 100 / all);
    }

    private void run() {
        try {
            if (this.config.structures && this.config.mapStructures) {
                this.prepareRegions();
                this.work(this::structureWorker);
            }
            LodHorizon horizon = this.generator.horizon();
            if (horizon != null && this.running) {
                this.phase = "horizon";
                this.done.set(0);
                this.total = this.countChunks();
                this.ring = 0;
                this.ringIndex = 0;
                this.work(() -> this.horizonWorker(horizon));
            }
            long seconds = (System.currentTimeMillis() - this.startedAt) / 1000;
            this.report(this.running ? LodChat.success(String.format("Prepared %d chunks around %d, %d in %d s", this.radius,
                    this.centerX * 16 + 8, this.centerZ * 16 + 8, seconds)) : LodChat.info("Preparing stopped at " + this.progress()));
        } catch (Throwable t) {
            LodGen.LOGGER.error("Preparing failed", t);
            this.report(LodChat.error("Preparing failed: " + t));
        } finally {
            synchronized (LodPregen.class) {
                if (current == this) {
                    current = null;
                }
            }
        }
    }

    /** Runs the task on the threads until they are all done, reporting the progress now and then */
    private void work(Runnable task) throws InterruptedException {
        AtomicInteger number = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < this.threads; i++) {
            Thread thread = new LodWorkerThread(task, "LodGen-Pregen-" + number.incrementAndGet());
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
            workers.add(thread);
        }
        long lastReport = System.currentTimeMillis();
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                worker.join(1_000);
                if (System.currentTimeMillis() - lastReport >= REPORT_MILLIS) {
                    lastReport = System.currentTimeMillis();
                    this.report(LodChat.info("Preparing: " + this.progress()));
                }
            }
        }
    }

    /** Waits while the generation is paused or the server slow; @return false once stopped */
    private boolean mayGo() {
        while (this.running && (this.generator.isPaused() || this.generator.isThrottled())) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return this.running;
    }

    private void prepareRegions() {
        int regionRadius = (this.radius >> StructureScan.REGION_SHIFT) + 1;
        int cx = this.centerX >> StructureScan.REGION_SHIFT;
        int cz = this.centerZ >> StructureScan.REGION_SHIFT;
        List<Long> todo = new ArrayList<>();
        for (int x = cx - regionRadius; x <= cx + regionRadius; x++) {
            for (int z = cz - regionRadius; z <= cz + regionRadius; z++) {
                long region = LodGenerator.pack(x, z);
                if (StructureScan.overlaps(this.border, x, z) && !StructureMarkers.isScanned(this.level, region)) {
                    todo.add(region);
                }
            }
        }
        todo.sort(Comparator.comparingLong(region -> {
            long dx = ChunkPos.getX(region) - cx;
            long dz = ChunkPos.getZ(region) - cz;
            return dx * dx + dz * dz;
        }));
        synchronized (this) {
            this.regions = todo;
            this.nextRegion = 0;
        }
        this.total = todo.size();
    }

    private synchronized long nextRegion() {
        return this.nextRegion < this.regions.size() ? this.regions.get(this.nextRegion++) : Long.MIN_VALUE;
    }

    private void structureWorker() {
        LodStructures finder = new LodStructures(this.level);
        try {
            while (this.mayGo()) {
                long region = this.nextRegion();
                if (region == Long.MIN_VALUE || !StructureScan.scanRegion(finder, this.level, region, () -> this.running)) {
                    return;
                }
                this.done.incrementAndGet();
            }
        } catch (InterruptedException e) {
            // Stopped
        }
    }

    private void horizonWorker(LodHorizon horizon) {
        LodHorizonSampler sampler = horizon.newSampler(this.level);
        while (this.mayGo()) {
            long chunk = this.nextChunk();
            if (chunk == Long.MIN_VALUE) {
                return;
            }
            int x = ChunkPos.getX(chunk);
            int z = ChunkPos.getZ(chunk);
            long dx = x - this.centerX;
            long dz = z - this.centerZ;
            horizon.pregenerate(this.level, sampler, x, z, LodHorizon.stepFor(dx * dx + dz * dz));
            this.done.incrementAndGet();
        }
    }

    /** The next chunk of the walk within the radius and the world border, nearest first; MIN_VALUE once done */
    private synchronized long nextChunk() {
        long radiusSquared = (long) this.radius * this.radius;
        long reach = Math.min(this.radius, this.border.reach(this.centerX, this.centerZ));
        while (this.ring <= reach) {
            int size = Math.max(1, 8 * this.ring);
            if (this.ringIndex >= size) {
                this.ring++;
                this.ringIndex = 0;
                continue;
            }
            int index = this.ringIndex++;
            int dx = 0;
            int dz = 0;
            if (this.ring > 0) {
                int side = index / (2 * this.ring);
                int offset = index % (2 * this.ring);
                switch (side) {
                    case 0 -> { dx = -this.ring + offset; dz = -this.ring; }
                    case 1 -> { dx = this.ring; dz = -this.ring + offset; }
                    case 2 -> { dx = this.ring - offset; dz = this.ring; }
                    default -> { dx = -this.ring; dz = this.ring - offset; }
                }
            }
            int x = this.centerX + dx;
            int z = this.centerZ + dz;
            if ((long) dx * dx + (long) dz * dz <= radiusSquared && this.border.contains(x, z)) {
                return LodGenerator.pack(x, z);
            }
        }
        return Long.MIN_VALUE;
    }

    /** How many chunks the walk goes through */
    private long countChunks() {
        long count = 0;
        for (int dx = -this.radius; dx <= this.radius; dx++) {
            int x = this.centerX + dx;
            if (x < this.border.minX() || x > this.border.maxX()) {
                continue;
            }
            int half = (int) Math.floor(Math.sqrt((double) this.radius * this.radius - (double) dx * dx));
            int minZ = Math.max(this.centerZ - half, this.border.minZ());
            int maxZ = Math.min(this.centerZ + half, this.border.maxZ());
            count += Math.max(0, maxZ - minZ + 1);
        }
        return count;
    }

    private void report(Component message) {
        LodGen.LOGGER.info(message.getString());
        this.server.execute(() -> this.source.sendSuccess(() -> message, true));
    }
}
