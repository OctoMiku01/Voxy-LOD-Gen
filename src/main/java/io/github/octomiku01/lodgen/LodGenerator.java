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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
    private record Viewer(UUID id, String name, boolean host, ServerLevel level, int chunkX, int chunkZ, float yaw, @Nullable ServerPlayer player) {
    }

    /** A walk through square rings around a player, see {@link #walk} */
    private static final class Cursor {
        int ring = -1;
        int ringIndex;
        /** Where on the current ring the player looks; the ring is walked outward from there to both sides */
        int ringStart;
        volatile int shownRing = -1;

        void start(int ring, float yaw) {
            this.ring = ring;
            this.ringIndex = 0;
            this.ringStart = ringIndexToward(yaw, ring);
        }
    }

    /**
     * One player's progress in one dimension: which chunks they got with features, and which only as terrain (those
     * are sent again with features once the player comes close enough).
     */
    private static final class Progress {
        /** Marks the file format that stores both sets; older files only have terrain LODs */
        private static final int FORMAT_TWO_LEVELS = -2;

        final Path file;
        final LongOpenHashSet done = new LongOpenHashSet(); // with features; guarded by itself
        final LongOpenHashSet doneTerrain = new LongOpenHashSet(); // terrain only; guarded by done
        volatile boolean dirty;
        /** Set by workers to restart the search at the player */
        volatile boolean rescan;
        volatile long pausedUntil;
        // scheduler thread only
        int centerX;
        int centerZ;
        /** Terrain everywhere; in the single pass also the details up to featureRadius */
        final Cursor terrain = new Cursor();
        /** The details up to featureRadius, when terrain comes first */
        final Cursor details = new Cursor();
        double tokens;
        long lastRefill;

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

        /**
         * @param features whether the LOD must include features; a terrain-only one is good enough otherwise
         */
        boolean isDone(long key, boolean features) {
            synchronized (this.done) {
                return this.done.contains(key) || !features && this.doneTerrain.contains(key);
            }
        }

        void markDone(long key, boolean features) {
            synchronized (this.done) {
                if (features) {
                    // The terrain preview is replaced, so each chunk counts once
                    this.done.add(key);
                    this.doneTerrain.remove(key);
                } else {
                    this.doneTerrain.add(key);
                }
            }
            this.dirty = true;
        }

        int doneCount() {
            synchronized (this.done) {
                return this.done.size() + this.doneTerrain.size();
            }
        }

        void clear() {
            synchronized (this.done) {
                this.done.clear();
                this.doneTerrain.clear();
            }
            this.dirty = true;
            this.rescan = true;
        }

        private void load() {
            if (!Files.exists(this.file)) {
                return;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file)))) {
                int first = in.readInt();
                if (first == FORMAT_TWO_LEVELS) {
                    readSet(in, this.done, in.readInt());
                    readSet(in, this.doneTerrain, in.readInt());
                } else {
                    // Written before features existed: all of them are terrain only
                    readSet(in, this.doneTerrain, first);
                }
                // Files from before terrain previews were dropped once replaced
                this.doneTerrain.removeAll(this.done);
            } catch (IOException e) {
                LodGen.LOGGER.error("Could not read {}, LODs will be generated again", this.file, e);
                this.done.clear();
                this.doneTerrain.clear();
            }
        }

        private static void readSet(DataInputStream in, LongOpenHashSet set, int count) throws IOException {
            for (int i = 0; i < count; i++) {
                set.add(in.readLong());
            }
        }

        private static void writeSet(DataOutputStream out, LongOpenHashSet set) throws IOException {
            out.writeInt(set.size());
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                out.writeLong(it.nextLong());
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
                        out.writeInt(FORMAT_TWO_LEVELS);
                        writeSet(out, this.done);
                        writeSet(out, this.doneTerrain);
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

    private enum Join { ADDED, ALREADY, TOO_LATE }

    /** A square of chunks to build (a single chunk without features, a tile with them), and everyone it goes to */
    private static final class Task {
        final int minX;
        final int minZ;
        final int size;
        final boolean features;
        /** Chunks that exist as real chunks on disk: built for context, but not delivered */
        final boolean[] saved;
        private final List<Recipient> recipients = new ArrayList<>(2);
        private boolean delivering;

        Task(int minX, int minZ, int size, boolean features, boolean[] saved) {
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.features = features;
            this.saved = saved;
        }

        long chunkKey(int index) {
            return pack(this.minX + index / this.size, this.minZ + index % this.size);
        }

        synchronized Join add(Recipient recipient) {
            if (this.delivering) {
                return Join.TOO_LATE;
            }
            for (Recipient existing : this.recipients) {
                if (existing.progress == recipient.progress) {
                    return Join.ALREADY;
                }
            }
            this.recipients.add(recipient);
            return Join.ADDED;
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
        final Map<Long, Task> queued = new ConcurrentHashMap<>(); // terrain only, by chunk
        final Map<Long, Task> tiles = new ConcurrentHashMap<>(); // with features, by tile
        @Nullable LodSink hostSink; // scheduler thread only
        boolean hostSinkCreated;

        Dimension(ServerLevel level) {
            this.level = level;
            this.builder = LodChunkBuilder.create(level, LodGenerator.this.config.structures, LodGenerator.this.config.keepUnderground);
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
    private final ThreadPoolExecutor workers;
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
        this.workers = new ThreadPoolExecutor(config.threads, config.threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
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
            current.add(new Viewer(player.getUUID(), player.getScoreboardName(), host, (ServerLevel) player.level(), pos.x(), pos.z(), player.getYRot(), player));
        }
        if (current.isEmpty() && this.benchmark) {
            current.add(new Viewer(NO_PLAYER, "benchmark", false, this.server.overworld(), 0, 0, 0, null));
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
                this.rate, this.inFlight.get(), this.threads(), current.size(), this.sent.get()));
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
                String rings = ringStatus(progress.terrain.shownRing, this.config.radius);
                if (this.config.terrainFirst) {
                    rings = "terrain " + rings + ", details " + ringStatus(progress.details.shownRing, this.config.featureRadius);
                }
                text.append(String.format("; %s in %s: %s, %d LOD chunks", viewer.name, viewer.level.dimension().identifier(),
                        rings, progress.doneCount()));
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
                this.adjustThreads();
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
        return this.threads() * 2;
    }

    // While the game is paused it needs no CPU time, so more workers can run
    private int threads() {
        return this.server.isPaused() ? Math.max(this.config.threads, this.config.threadsWhilePaused) : this.config.threads;
    }

    private void adjustThreads() {
        int wanted = this.threads();
        if (wanted > this.workers.getMaximumPoolSize()) {
            this.workers.setMaximumPoolSize(wanted);
            this.workers.setCorePoolSize(wanted);
        } else if (wanted < this.workers.getMaximumPoolSize()) {
            // Extra workers finish their chunk, then stop
            this.workers.setCorePoolSize(wanted);
            this.workers.setMaximumPoolSize(wanted);
        }
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

    private static String ringStatus(int ring, int limit) {
        return ring < 0 ? "starting" : ring > limit ? "done" : "ring " + ring + "/" + limit;
    }

    /** The position on a square ring (see {@link #queueNext}) in the direction of the given yaw */
    private static int ringIndexToward(float yaw, int ring) {
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        double scale = ring / Math.max(Math.abs(x), Math.abs(z));
        int dx = Math.clamp(Math.round(x * scale), -ring, ring);
        int dz = Math.clamp(Math.round(z * scale), -ring, ring);
        if (dz == -ring && dx < ring) {
            return dx + ring; // side 0
        } else if (dx == ring && dz < ring) {
            return 2 * ring + dz + ring; // side 1
        } else if (dz == ring && dx > -ring) {
            return 4 * ring + ring - dx; // side 2
        }
        return 6 * ring + ring - dz; // side 3
    }

    /**
     * Decides what a player gets next. In a single pass, the rings around them carry details up to featureRadius and
     * terrain beyond. With terrainFirst, terrain covers everything quickly first and the details follow: the terrain
     * stays twice as far ahead, so close details do not wait for the whole radius.
     *
     * @return whether a chunk was queued for them
     */
    private boolean queueNext(Viewer viewer, Progress progress) {
        if (progress.tokens < 1) {
            return false;
        }
        int minRing = this.server.getPlayerList().getViewDistance() + this.config.viewDistanceMargin + 1;
        if (progress.terrain.ring < 0 || progress.rescan
                || Math.max(Math.abs(viewer.chunkX - progress.centerX), Math.abs(viewer.chunkZ - progress.centerZ)) >= RESCAN_DISTANCE) {
            progress.centerX = viewer.chunkX;
            progress.centerZ = viewer.chunkZ;
            progress.terrain.start(minRing, viewer.yaw);
            progress.details.start(minRing, viewer.yaw);
            progress.rescan = false;
        }
        if (!this.config.terrainFirst) {
            return this.walk(viewer, progress, progress.terrain, this.config.radius, false);
        }
        while (true) {
            boolean terrainDone = progress.terrain.ring > this.config.radius;
            boolean detailsDone = progress.details.ring > this.config.featureRadius;
            if (terrainDone && detailsDone) {
                return false;
            }
            boolean details = terrainDone || !detailsDone && 2 * progress.details.ring <= progress.terrain.ring;
            Cursor cursor = details ? progress.details : progress.terrain;
            int limit = details ? this.config.featureRadius : this.config.radius;
            if (this.walk(viewer, progress, cursor, limit, details)) {
                return true;
            }
            if (cursor.ring <= limit) {
                // Waiting (send rate, Voxy not ready, a tile being delivered): try again later
                return false;
            }
        }
    }

    /**
     * Walks square rings around the player, nearest first and starting where they look, to the next chunk they still
     * need.
     *
     * @param onlyDetails every chunk on this walk gets details; otherwise only up to featureRadius in a single pass
     * @return whether a chunk was queued for them
     */
    private boolean walk(Viewer viewer, Progress progress, Cursor cursor, int limit, boolean onlyDetails) {
        Dimension dimension = this.dimensions.get(viewer.level.dimension());
        long radiusSquared = (long) limit * limit;
        while (cursor.ring <= limit) {
            int ring = cursor.ring;
            int index = cursor.ringIndex;
            cursor.shownRing = ring;
            if (index >= 8 * ring) {
                cursor.start(cursor.ring + 1, viewer.yaw);
                continue;
            }
            cursor.ringIndex++;
            // From where the player looks, alternately one step further to each side
            int step = (index + 1) / 2;
            index = Math.floorMod(cursor.ringStart + (index % 2 == 1 ? step : -step), 8 * ring);

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
            boolean features = onlyDetails || !this.config.terrainFirst && ring <= this.config.featureRadius;
            if (progress.isDone(key, features) || dimension.saved.exists(chunkX, chunkZ)) {
                continue;
            }

            LodSink sink = viewer.host ? dimension.hostSink()
                    : viewer.player == null ? LodSink.DISCARD : new NetworkSink(viewer.player, viewer.level.dimension());
            if (sink == null) {
                cursor.ringIndex--;
                return false;
            }
            Recipient recipient = new Recipient(sink, progress);

            // Features are built in tiles, terrain chunk by chunk
            int size = features ? this.config.tileSize : 1;
            int minX = Math.floorDiv(chunkX, size) * size;
            int minZ = Math.floorDiv(chunkZ, size) * size;
            Map<Long, Task> tasks = features ? dimension.tiles : dimension.queued;
            long taskKey = pack(minX, minZ);

            // Already being built for someone else: add this player to it
            Task task = tasks.get(taskKey);
            if (task == null) {
                boolean[] saved = new boolean[size * size];
                for (int i = 0; i < saved.length; i++) {
                    saved[i] = dimension.saved.exists(minX + i / size, minZ + i % size);
                }
                task = new Task(minX, minZ, size, features, saved);
                task.add(recipient);
                tasks.put(taskKey, task);
                progress.tokens -= this.wanted(task, progress);
                this.inFlight.incrementAndGet();
                Task queued = task;
                this.workers.execute(() -> this.build(dimension, queued, taskKey));
                return true;
            }
            switch (task.add(recipient)) {
                case ADDED -> {
                    progress.tokens -= this.wanted(task, progress);
                    return true;
                }
                case ALREADY -> {
                    // Another chunk of a tile this player already waits for
                }
                case TOO_LATE -> {
                    // Look at it again once that delivery is over
                    cursor.ringIndex--;
                    return false;
                }
            }
        }
        cursor.shownRing = cursor.ring;
        return false;
    }

    /** How many chunks of the task the player will get, for their send rate */
    private int wanted(Task task, Progress progress) {
        int count = 0;
        for (int index = 0; index < task.saved.length; index++) {
            if (!task.saved[index] && !progress.isDone(task.chunkKey(index), task.features)) {
                count++;
            }
        }
        return count;
    }

    private void build(Dimension dimension, Task task, long taskKey) {
        try {
            if (!this.running) {
                return;
            }
            List<LodChunk> chunks = null;
            try {
                chunks = task.features
                        ? dimension.builder.buildTile(task.minX, task.minZ, task.size, () -> !this.running)
                        : List.of(dimension.builder.build(new ChunkPos(task.minX, task.minZ)));
                this.generated.addAndGet(chunks.size());
            } catch (Throwable t) {
                if (!this.running) {
                    // The world is closing (and workers get interrupted): nothing is lost, it is built next time
                    return;
                }
                if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LodGen.LOGGER.error("Could not build the LOD for chunk {}, {}", task.minX, task.minZ, t);
                }
            }
            (task.features ? dimension.tiles : dimension.queued).remove(taskKey);
            if (!this.running) {
                return;
            }
            for (Recipient recipient : task.startDelivery()) {
                this.deliver(task, chunks, recipient);
            }
        } finally {
            this.inFlight.decrementAndGet();
            synchronized (this.workerFinished) {
                this.workerFinished.notify();
            }
        }
    }

    private void deliver(Task task, @Nullable List<LodChunk> chunks, Recipient recipient) {
        for (int index = 0; index < task.saved.length; index++) {
            long key = task.chunkKey(index);
            if (task.saved[index] || recipient.progress.isDone(key, task.features)) {
                continue;
            }
            if (chunks == null) {
                // Skip it instead of failing on it forever
                recipient.progress.markDone(key, task.features);
                continue;
            }
            try {
                if (!recipient.sink.accept(chunks.get(index))) {
                    // Not ready (Voxy still loading, player left or switched dimension): try again later
                    recipient.progress.pausedUntil = System.currentTimeMillis() + RETRY_MILLIS;
                    recipient.progress.rescan = true;
                    return;
                }
                recipient.progress.markDone(key, task.features);
                if (recipient.sink instanceof NetworkSink) {
                    this.sent.incrementAndGet();
                }
            } catch (Throwable t) {
                recipient.progress.markDone(key, task.features);
                if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LodGen.LOGGER.error("Could not deliver the LOD for chunk {}, {}", task.minX + index / task.size, task.minZ + index % task.size, t);
                }
            }
        }
    }
}
