package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Picks the chunks around the player that still need an LOD, nearest first, and builds them on a worker pool.
 * <p>
 * Threads: the server thread only publishes where the player is ({@link #onServerTick}); one scheduler thread walks
 * outward from there in square rings and hands chunks to the workers; the workers build and ingest them.
 */
final class LodGenerator {
    private static final long SAVE_INTERVAL_MILLIS = 60_000;
    private static final long STATS_INTERVAL_MILLIS = 10_000;
    private static final long VOXY_RETRY_MILLIS = 2_000;
    /** Moving this many chunks restarts the search at the player, so nearby gaps are filled first */
    private static final int RESCAN_DISTANCE = 8;
    private static final int MAX_LOGGED_ERRORS = 10;

    private record Target(ServerLevel level, int chunkX, int chunkZ, int viewDistance) {
    }

    private final class Dimension {
        final ServerLevel level;
        final @Nullable LodChunkBuilder builder;
        final SavedChunks saved;
        final Path progressFile;
        final LongOpenHashSet done = new LongOpenHashSet(); // guarded by itself
        final LongOpenHashSet queued = new LongOpenHashSet(); // guarded by itself
        volatile boolean dirty;
        /** Set by workers to restart the search at the player */
        volatile boolean rescan;
        // search cursor, scheduler thread only
        int centerX;
        int centerZ;
        int ring = -1;
        int ringIndex;

        Dimension(ServerLevel level) {
            this.level = level;
            LodSink sink = LodGenerator.this.benchmark ? LodSink.DISCARD : VoxySink.create(level);
            this.builder = sink == null ? null : LodChunkBuilder.create(level, sink);
            Path worldRoot = LodGenerator.this.server.getWorldPath(LevelResource.ROOT);
            this.saved = new SavedChunks(DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region"));
            this.progressFile = worldRoot.resolve("lodgen").resolve(level.dimension().identifier().getNamespace())
                    .resolve(level.dimension().identifier().getPath() + ".bin");
            this.load();
            if (this.builder == null) {
                LodGen.LOGGER.info("{}: no LODs, the dimension is not supported", level.dimension().identifier());
            }
        }

        boolean isDone(long key) {
            synchronized (this.done) {
                return this.done.contains(key);
            }
        }

        void markDone(long key) {
            synchronized (this.done) {
                this.done.add(key);
            }
            this.dirty = true;
        }

        int doneCount() {
            synchronized (this.done) {
                return this.done.size();
            }
        }

        private void load() {
            if (!Files.exists(this.progressFile)) {
                return;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.progressFile)))) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    this.done.add(in.readLong());
                }
            } catch (IOException e) {
                LodGen.LOGGER.error("Could not read {}, LODs will be generated again", this.progressFile, e);
                this.done.clear();
            }
        }

        void save() {
            if (!this.dirty) {
                return;
            }
            this.dirty = false;
            try {
                Files.createDirectories(this.progressFile.getParent());
                Path temp = this.progressFile.resolveSibling(this.progressFile.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    synchronized (this.done) {
                        out.writeInt(this.done.size());
                        for (LongIterator it = this.done.iterator(); it.hasNext(); ) {
                            out.writeLong(it.nextLong());
                        }
                    }
                }
                Files.move(temp, this.progressFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                this.dirty = true;
                LodGen.LOGGER.error("Could not save {}", this.progressFile, e);
            }
        }
    }

    private final MinecraftServer server;
    private final LodConfig config;
    private final boolean benchmark;
    private final ExecutorService workers;
    private final Thread scheduler;
    private final Map<ResourceKey<Level>, Dimension> dimensions = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong generated = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    /** Workers wake the scheduler through this as soon as they finish a chunk */
    private final Object workerFinished = new Object();
    private volatile @Nullable Target target;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile long retryAt;
    private volatile double rate;

    /**
     * @param benchmark generate around the world spawn without a player and discard the result, to measure speed
     */
    LodGenerator(MinecraftServer server, LodConfig config, boolean benchmark) {
        this.server = server;
        this.config = config;
        this.benchmark = benchmark;
        AtomicInteger threadNumber = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(config.threads, runnable -> {
            Thread thread = new Thread(runnable, "LodGen-Worker-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.scheduler = new Thread(this::schedulerLoop, "LodGen-Scheduler");
        this.scheduler.setDaemon(true);
        this.scheduler.start();
        LodGen.LOGGER.info("Generating LODs up to {} chunks away with {} threads", config.radius, config.threads);
    }

    static long pack(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    /** Server thread: publish where the player is. Only the first player counts (single player). */
    void onServerTick() {
        ServerPlayer player = this.server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (player == null) {
            if (!this.benchmark) {
                this.target = null;
            } else if (this.target == null) {
                this.target = new Target(this.server.overworld(), 0, 0, this.server.getPlayerList().getViewDistance());
            }
            return;
        }
        ChunkPos pos = player.chunkPosition();
        Target current = this.target;
        ServerLevel level = (ServerLevel) player.level();
        int viewDistance = this.server.getPlayerList().getViewDistance();
        if (current == null || current.level != level || current.chunkX != pos.x() || current.chunkZ != pos.z() || current.viewDistance != viewDistance) {
            this.target = new Target(level, pos.x(), pos.z(), viewDistance);
        }
    }

    void setPaused(boolean paused) {
        this.paused = paused;
    }

    String status() {
        Target current = this.target;
        if (current == null) {
            return "no player";
        }
        Dimension dimension = this.dimensions.get(current.level.dimension());
        if (dimension == null) {
            return "starting";
        }
        if (dimension.builder == null) {
            return current.level.dimension().identifier() + ": not supported";
        }
        String ring = dimension.ring < 0 ? "-" : dimension.ring > this.config.radius ? "done" : dimension.ring + "/" + this.config.radius;
        return String.format("%s%s: ring %s, %d LOD chunks, %.1f chunks/s, %d in progress, %d threads",
                this.paused ? "[paused] " : "", current.level.dimension().identifier(), ring, dimension.doneCount(),
                this.rate, this.inFlight.get(), this.config.threads);
    }

    void stop() {
        this.running = false;
        this.scheduler.interrupt();
        this.workers.shutdown();
        try {
            if (!this.workers.awaitTermination(10, TimeUnit.SECONDS)) {
                this.workers.shutdownNow();
            }
            this.scheduler.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.dimensions.values().forEach(Dimension::save);
    }

    private void schedulerLoop() {
        long lastSave = System.currentTimeMillis();
        long lastStats = lastSave;
        long generatedAtLastStats = 0;
        while (this.running) {
            boolean moreWork = false;
            try {
                Target current = this.target;
                if (current != null && !this.paused && System.currentTimeMillis() >= this.retryAt) {
                    Dimension dimension = this.dimensions.computeIfAbsent(current.level.dimension(), key -> new Dimension(current.level));
                    if (dimension.builder != null) {
                        moreWork = this.fillQueue(dimension, current);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastStats >= STATS_INTERVAL_MILLIS) {
                    long total = this.generated.get();
                    this.rate = (total - generatedAtLastStats) * 1000.0 / (now - lastStats);
                    if (total != generatedAtLastStats) {
                        LodGen.LOGGER.info("{}", this.status());
                    }
                    generatedAtLastStats = total;
                    lastStats = now;
                }
                if (now - lastSave >= SAVE_INTERVAL_MILLIS) {
                    this.dimensions.values().forEach(Dimension::save);
                    lastSave = now;
                }
                if (moreWork) {
                    // The queue is full: continue as soon as a worker is done
                    synchronized (this.workerFinished) {
                        if (this.inFlight.get() >= this.capacity()) {
                            this.workerFinished.wait(50);
                        }
                    }
                } else {
                    Thread.sleep(250);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LodGen.LOGGER.error("LOD scheduler failed", e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /**
     * Walks square rings around the player, nearest first, and queues chunks until the workers have enough to do.
     *
     * @return false once every chunk within the radius is done
     */
    private boolean fillQueue(Dimension dimension, Target target) {
        int minRing = target.viewDistance + this.config.viewDistanceMargin + 1;
        if (dimension.ring < 0 || dimension.rescan || Math.max(Math.abs(target.chunkX - dimension.centerX), Math.abs(target.chunkZ - dimension.centerZ)) >= RESCAN_DISTANCE) {
            dimension.centerX = target.chunkX;
            dimension.centerZ = target.chunkZ;
            dimension.ring = minRing;
            dimension.ringIndex = 0;
            dimension.rescan = false;
        }

        int capacity = this.capacity();
        long radiusSquared = (long) this.config.radius * this.config.radius;
        while (this.inFlight.get() < capacity && dimension.ring <= this.config.radius) {
            int ring = dimension.ring;
            int index = dimension.ringIndex;
            if (index >= 8 * ring) {
                dimension.ring++;
                dimension.ringIndex = 0;
                continue;
            }
            dimension.ringIndex++;

            int side = index / (2 * ring);
            int offset = index % (2 * ring);
            int dx;
            int dz;
            switch (side) {
                case 0 -> { dx = -ring + offset; dz = -ring; }
                case 1 -> { dx = ring; dz = -ring + offset; }
                case 2 -> { dx = ring - offset; dz = ring; }
                default -> { dx = -ring; dz = ring - offset; }
            }
            if ((long) dx * dx + (long) dz * dz > radiusSquared) {
                continue;
            }
            int chunkX = dimension.centerX + dx;
            int chunkZ = dimension.centerZ + dz;
            long key = pack(chunkX, chunkZ);
            if (dimension.isDone(key) || dimension.saved.exists(chunkX, chunkZ)) {
                continue;
            }
            synchronized (dimension.queued) {
                if (!dimension.queued.add(key)) {
                    continue;
                }
            }
            this.inFlight.incrementAndGet();
            this.workers.execute(() -> this.build(dimension, chunkX, chunkZ, key));
        }
        return dimension.ring <= this.config.radius;
    }

    private int capacity() {
        return this.config.threads * 2;
    }

    private void build(Dimension dimension, int chunkX, int chunkZ, long key) {
        try {
            if (!this.running) {
                return;
            }
            if (dimension.builder.build(new ChunkPos(chunkX, chunkZ))) {
                dimension.markDone(key);
                this.generated.incrementAndGet();
            } else {
                // Voxy is not ready (e.g. still loading): pause and search again from the player afterwards
                this.retryAt = System.currentTimeMillis() + VOXY_RETRY_MILLIS;
                dimension.rescan = true;
            }
        } catch (Throwable t) {
            // Skip this chunk instead of failing on it forever
            dimension.markDone(key);
            if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                LodGen.LOGGER.error("Could not build the LOD for chunk {}, {}", chunkX, chunkZ, t);
            }
        } finally {
            synchronized (dimension.queued) {
                dimension.queued.remove(key);
            }
            this.inFlight.decrementAndGet();
            synchronized (this.workerFinished) {
                this.workerFinished.notify();
            }
        }
    }
}
