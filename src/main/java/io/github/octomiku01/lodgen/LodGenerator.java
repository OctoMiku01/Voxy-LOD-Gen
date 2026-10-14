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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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
 * Picks the chunks around each player that still need an LOD, nearest first, builds them on a worker pool and
 * delivers them: straight to Voxy for the player hosting a single player world, over the network to every other
 * player whose client has this mod and Voxy. A chunk several players need is built once.
 * <p>
 * Threads: the server thread only publishes where the players are ({@link #onServerTick}); one scheduler thread walks
 * outward from each of them in square rings and hands chunks to the workers; the workers build and deliver them.
 */
final class LodGenerator {
    private static final long SAVE_INTERVAL_MILLIS = 60_000;
    private static final long STATS_INTERVAL_MILLIS = 10_000;
    private static final long RETRY_MILLIS = 2_000;
    /** Moving this many chunks restarts the search at the player, so nearby gaps are filled first */
    private static final int RESCAN_DISTANCE = 8;
    private static final int MAX_LOGGED_ERRORS = 10;
    /** Players without a profile, e.g. the benchmark, share this id */
    private static final UUID NO_PLAYER = new UUID(0, 0);

    /** Where a player who gets LODs is; published by the server thread */
    private record Viewer(UUID id, String name, boolean host, ServerLevel level, int chunkX, int chunkZ, @Nullable ServerPlayer player) {
    }

    /** One player's progress in one dimension */
    private static final class Progress {
        final Path file;
        final LongOpenHashSet done = new LongOpenHashSet(); // guarded by itself
        volatile boolean dirty;
        /** Set by workers to restart the search at the player */
        volatile boolean rescan;
        volatile long pausedUntil;
        // scheduler thread only
        int centerX;
        int centerZ;
        int ring = -1;
        int ringIndex;
        double tokens;
        long lastRefill;
        volatile int shownRing = -1;

        Progress(Path file, @Nullable Path legacyFile) {
            this.file = file;
            if (!Files.exists(file) && legacyFile != null && Files.exists(legacyFile)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.move(legacyFile, file);
                } catch (IOException e) {
                    LodGen.LOGGER.warn("Could not move {} to {}", legacyFile, file, e);
                }
            }
            this.load();
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

        void clear() {
            synchronized (this.done) {
                this.done.clear();
            }
            this.dirty = true;
            this.rescan = true;
        }

        private void load() {
            if (!Files.exists(this.file)) {
                return;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file)))) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    this.done.add(in.readLong());
                }
            } catch (IOException e) {
                LodGen.LOGGER.error("Could not read {}, LODs will be generated again", this.file, e);
                this.done.clear();
            }
        }

        void save() {
            if (!this.dirty) {
                return;
            }
            this.dirty = false;
            try {
                Files.createDirectories(this.file.getParent());
                Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    synchronized (this.done) {
                        out.writeInt(this.done.size());
                        for (LongIterator it = this.done.iterator(); it.hasNext(); ) {
                            out.writeLong(it.nextLong());
                        }
                    }
                }
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                this.dirty = true;
                LodGen.LOGGER.error("Could not save {}", this.file, e);
            }
        }
    }

    private record Recipient(LodSink sink, Progress progress) {
    }

    /** One chunk to build, and everyone it goes to */
    private static final class Task {
        private final List<Recipient> recipients = new ArrayList<>(2);
        private boolean delivering;

        synchronized boolean add(Recipient recipient) {
            if (this.delivering) {
                return false;
            }
            this.recipients.add(recipient);
            return true;
        }

        synchronized List<Recipient> startDelivery() {
            this.delivering = true;
            return this.recipients;
        }
    }

    private final class Dimension {
        final ServerLevel level;
        final @Nullable LodChunkBuilder builder;
        final SavedChunks saved; // scheduler thread only
        final Map<Long, Task> queued = new ConcurrentHashMap<>();
        @Nullable LodSink hostSink; // scheduler thread only
        boolean hostSinkCreated;

        Dimension(ServerLevel level) {
            this.level = level;
            this.builder = LodChunkBuilder.create(level);
            Path worldRoot = LodGenerator.this.server.getWorldPath(LevelResource.ROOT);
            this.saved = new SavedChunks(DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region"));
            if (this.builder == null) {
                LodGen.LOGGER.info("{}: no LODs, the dimension is not supported", level.dimension().identifier());
            }
        }

        @Nullable LodSink hostSink() {
            if (!this.hostSinkCreated) {
                this.hostSinkCreated = true;
                this.hostSink = VoxySink.create(this.level);
            }
            return this.hostSink;
        }
    }

    private final MinecraftServer server;
    private final LodConfig config;
    private final boolean benchmark;
    /** Voxy runs in this game instance, so the host gets LODs without the network */
    private final boolean localVoxy;
    private final ExecutorService workers;
    private final Thread scheduler;
    private final Map<ResourceKey<Level>, Dimension> dimensions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<ResourceKey<Level>, Progress>> progress = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    /** Workers wake the scheduler through this as soon as they finish a chunk */
    private final Object workerFinished = new Object();
    private volatile List<Viewer> viewers = List.of();
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile double rate;
    private int turn; // scheduler thread only

    /**
     * @param benchmark generate around the world spawn without a player and discard the result, to measure speed
     */
    LodGenerator(MinecraftServer server, LodConfig config, boolean benchmark) {
        this.server = server;
        this.config = config;
        this.benchmark = benchmark;
        this.localVoxy = !server.isDedicatedServer() && FabricLoader.getInstance().isModLoaded("voxy");
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

    /** Server thread: publish who gets LODs and where they are */
    void onServerTick() {
        List<Viewer> current = new ArrayList<>();
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            boolean host = this.localVoxy && this.server.isSingleplayerOwner(player.nameAndId());
            if (!host && !(this.config.sendToPlayers && ServerPlayNetworking.canSend(player, LodChunkPayload.TYPE))) {
                continue;
            }
            ChunkPos pos = player.chunkPosition();
            current.add(new Viewer(player.getUUID(), player.getScoreboardName(), host, (ServerLevel) player.level(), pos.x(), pos.z(), player));
        }
        if (current.isEmpty() && this.benchmark) {
            current.add(new Viewer(NO_PLAYER, "benchmark", false, this.server.overworld(), 0, 0, null));
        }
        this.viewers = List.copyOf(current);
    }

    void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Forgets which LODs this player already got in their current dimension, so they are sent again */
    boolean reset(ServerPlayer player) {
        Viewer viewer = this.viewerOf(player.getUUID());
        if (viewer == null) {
            return false;
        }
        this.progressOf(viewer).clear();
        return true;
    }

    String status(@Nullable ServerPlayer asking) {
        List<Viewer> current = this.viewers;
        StringBuilder text = new StringBuilder(this.paused ? "[paused] " : "");
        text.append(String.format("%.1f chunks/s, %d in progress, %d threads, %d player(s), %d sent over the network",
                this.rate, this.inFlight.get(), this.config.threads, current.size(), this.sent.get()));
        Viewer viewer = asking == null ? null : this.viewerOf(asking.getUUID());
        if (viewer == null && current.size() == 1) {
            viewer = current.getFirst();
        }
        if (viewer != null) {
            Dimension dimension = this.dimensions.get(viewer.level.dimension());
            if (dimension != null && dimension.builder == null) {
                text.append("; ").append(viewer.level.dimension().identifier()).append(" is not supported");
            } else {
                Progress progress = this.progressOf(viewer);
                int ring = progress.shownRing;
                text.append(String.format("; %s in %s: %s, %d LOD chunks", viewer.name, viewer.level.dimension().identifier(),
                        ring < 0 ? "starting" : ring > this.config.radius ? "done" : "ring " + ring + "/" + this.config.radius,
                        progress.doneCount()));
            }
        } else if (asking != null) {
            text.append("; you get no LODs (this mod or Voxy is missing on your client)");
        }
        return text.toString();
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
        this.saveAll();
    }

    private @Nullable Viewer viewerOf(UUID id) {
        for (Viewer viewer : this.viewers) {
            if (viewer.id.equals(id)) {
                return viewer;
            }
        }
        return null;
    }

    private Progress progressOf(Viewer viewer) {
        ResourceKey<Level> key = viewer.level.dimension();
        return this.progress.computeIfAbsent(viewer.id, id -> new ConcurrentHashMap<>()).computeIfAbsent(key, k -> {
            Path lodgen = this.server.getWorldPath(LevelResource.ROOT).resolve("lodgen");
            String fileName = key.identifier().getPath() + ".bin";
            Path file = lodgen.resolve("players").resolve(viewer.id.toString()).resolve(key.identifier().getNamespace()).resolve(fileName);
            // Before LODs were sent to other players, the host's progress was stored directly in lodgen/
            Path legacy = viewer.host ? lodgen.resolve(key.identifier().getNamespace()).resolve(fileName) : null;
            return new Progress(file, legacy);
        });
    }

    private void saveAll() {
        for (Map<ResourceKey<Level>, Progress> byDimension : this.progress.values()) {
            byDimension.values().forEach(Progress::save);
        }
    }

    private void schedulerLoop() {
        long lastSave = System.currentTimeMillis();
        long lastStats = lastSave;
        long generatedAtLastStats = 0;
        while (this.running) {
            try {
                boolean full = false;
                if (!this.paused) {
                    full = this.fillQueue();
                }

                long now = System.currentTimeMillis();
                if (now - lastStats >= STATS_INTERVAL_MILLIS) {
                    long total = this.generated.get();
                    this.rate = (total - generatedAtLastStats) * 1000.0 / (now - lastStats);
                    if (total != generatedAtLastStats) {
                        LodGen.LOGGER.info("{}", this.status(null));
                    }
                    generatedAtLastStats = total;
                    lastStats = now;
                }
                if (now - lastSave >= SAVE_INTERVAL_MILLIS) {
                    this.saveAll();
                    lastSave = now;
                }

                if (full) {
                    // The workers have enough to do: continue as soon as one of them is done
                    synchronized (this.workerFinished) {
                        if (this.inFlight.get() >= this.capacity()) {
                            this.workerFinished.wait(50);
                        }
                    }
                } else {
                    // Nothing left, or players are waiting for their send rate
                    Thread.sleep(50);
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
     * Takes turns between the players, one chunk each, until the workers have enough to do.
     *
     * @return whether the queue is full
     */
    private boolean fillQueue() {
        List<Viewer> current = this.viewers;
        long now = System.currentTimeMillis();
        List<Viewer> active = new ArrayList<>(current.size());
        Map<Viewer, Progress> progresses = new HashMap<>();
        for (Viewer viewer : current) {
            Dimension dimension = this.dimensions.computeIfAbsent(viewer.level.dimension(), key -> new Dimension(viewer.level));
            Progress progress = this.progressOf(viewer);
            if (dimension.builder == null || now < progress.pausedUntil) {
                continue;
            }
            this.refillTokens(viewer, progress, now);
            active.add(viewer);
            progresses.put(viewer, progress);
        }

        // Workers usually free one slot at a time: start with a different player each time, or the first one in the
        // list would take every free slot
        int start = active.isEmpty() ? 0 : Math.floorMod(this.turn++, active.size());
        int capacity = this.capacity();
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (int i = 0; i < active.size(); i++) {
                if (this.inFlight.get() >= capacity) {
                    return true;
                }
                Viewer viewer = active.get((start + i) % active.size());
                if (this.queueNext(viewer, progresses.get(viewer))) {
                    progressed = true;
                }
            }
        }
        return this.inFlight.get() >= capacity;
    }

    private int capacity() {
        return this.config.threads * 2;
    }

    // The host gets LODs without the network; everyone else is limited to protect their connection
    private void refillTokens(Viewer viewer, Progress progress, long now) {
        if (viewer.host || viewer.player == null) {
            progress.tokens = Double.MAX_VALUE;
            return;
        }
        double limit = this.config.maxChunksPerSecondPerPlayer;
        if (progress.lastRefill == 0 || progress.tokens > limit) {
            progress.tokens = limit;
        } else {
            progress.tokens = Math.min(limit, progress.tokens + (now - progress.lastRefill) * limit / 1000.0);
        }
        progress.lastRefill = now;
    }

    /**
     * Walks square rings around the player, nearest first, to the next chunk they still need.
     *
     * @return whether a chunk was queued for them
     */
    private boolean queueNext(Viewer viewer, Progress progress) {
        if (progress.tokens < 1) {
            return false;
        }
        Dimension dimension = this.dimensions.get(viewer.level.dimension());
        int minRing = this.server.getPlayerList().getViewDistance() + this.config.viewDistanceMargin + 1;
        if (progress.ring < 0 || progress.rescan
                || Math.max(Math.abs(viewer.chunkX - progress.centerX), Math.abs(viewer.chunkZ - progress.centerZ)) >= RESCAN_DISTANCE) {
            progress.centerX = viewer.chunkX;
            progress.centerZ = viewer.chunkZ;
            progress.ring = minRing;
            progress.ringIndex = 0;
            progress.rescan = false;
        }

        long radiusSquared = (long) this.config.radius * this.config.radius;
        while (progress.ring <= this.config.radius) {
            int ring = progress.ring;
            int index = progress.ringIndex;
            progress.shownRing = ring;
            if (index >= 8 * ring) {
                progress.ring++;
                progress.ringIndex = 0;
                continue;
            }
            progress.ringIndex++;

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
            int chunkX = progress.centerX + dx;
            int chunkZ = progress.centerZ + dz;
            long key = pack(chunkX, chunkZ);
            if (progress.isDone(key) || dimension.saved.exists(chunkX, chunkZ)) {
                continue;
            }

            LodSink sink = viewer.host ? dimension.hostSink()
                    : viewer.player == null ? LodSink.DISCARD : new NetworkSink(viewer.player, viewer.level.dimension());
            if (sink == null) {
                progress.ringIndex--;
                return false;
            }
            Recipient recipient = new Recipient(sink, progress);

            // Already being built for someone else: add this player to it
            Task existing = dimension.queued.get(key);
            if (existing != null) {
                if (!existing.add(recipient)) {
                    // Too late to join: look at it again once that delivery is over
                    progress.ringIndex--;
                    return false;
                }
                progress.tokens--;
                return true;
            }
            Task task = new Task();
            task.add(recipient);
            dimension.queued.put(key, task);
            progress.tokens--;
            this.inFlight.incrementAndGet();
            this.workers.execute(() -> this.build(dimension, task, chunkX, chunkZ, key));
            return true;
        }
        progress.shownRing = progress.ring;
        return false;
    }

    private void build(Dimension dimension, Task task, int chunkX, int chunkZ, long key) {
        try {
            if (!this.running) {
                return;
            }
            LodChunk chunk = null;
            try {
                chunk = dimension.builder.build(new ChunkPos(chunkX, chunkZ));
                this.generated.incrementAndGet();
            } catch (Throwable t) {
                if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LodGen.LOGGER.error("Could not build the LOD for chunk {}, {}", chunkX, chunkZ, t);
                }
            }
            dimension.queued.remove(key);
            for (Recipient recipient : task.startDelivery()) {
                if (chunk == null) {
                    // Skip this chunk instead of failing on it forever
                    recipient.progress.markDone(key);
                    continue;
                }
                try {
                    if (recipient.sink.accept(chunk)) {
                        recipient.progress.markDone(key);
                        if (recipient.sink instanceof NetworkSink) {
                            this.sent.incrementAndGet();
                        }
                    } else {
                        // Not ready (Voxy still loading, player left or switched dimension): try again later
                        recipient.progress.pausedUntil = System.currentTimeMillis() + RETRY_MILLIS;
                        recipient.progress.rescan = true;
                    }
                } catch (Throwable t) {
                    recipient.progress.markDone(key);
                    if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                        LodGen.LOGGER.error("Could not deliver the LOD for chunk {}, {}", chunkX, chunkZ, t);
                    }
                }
            }
        } finally {
            this.inFlight.decrementAndGet();
            synchronized (this.workerFinished) {
                this.workerFinished.notify();
            }
        }
    }
}
