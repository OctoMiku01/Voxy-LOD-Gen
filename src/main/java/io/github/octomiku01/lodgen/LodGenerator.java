package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
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
import java.util.ArrayDeque;
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
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
    /**
     * The speed shown is the average over this long: chunks with features are finished a whole tile at a time, which
     * can take longer than the stats interval
     */
    private static final long RATE_WINDOW_MILLIS = 60_000;
    private static final long RETRY_MILLIS = 2_000;
    /** Moving this many chunks restarts the search at the player, so nearby gaps are filled first */
    private static final int RESCAN_DISTANCE = 8;
    private static final int MAX_LOGGED_ERRORS = 10;
    /** Players without a profile, e.g. the benchmark, share this id */
    private static final UUID NO_PLAYER = new UUID(0, 0);
    /** The most the 3D map may ask for around where it looks, in chunks */
    private static final int MAX_FOCUS_RADIUS = 64;
    /** The map sends where it looks every second while open; without that for this long it counts as closed */
    private static final long FOCUS_TIMEOUT_MILLIS = 5_000;
    /** Moving the map this many chunks restarts the search at the new place */
    private static final int FOCUS_MOVE = 4;

    /**
     * Where a player who gets LODs is; published by the server thread. While their 3D map is open, focusRadius is
     * above 0 and the chunks around where it looks come first.
     */
    private record Viewer(UUID id, String name, boolean host, ServerLevel level, int chunkX, int chunkZ, float yaw, int viewDistance,
            @Nullable ServerPlayer player, int focusX, int focusZ, int focusRadius) {
    }

    /** Where the 3D map of a player looks, as they sent it */
    private record MapFocus(ResourceKey<Level> dimension, int chunkX, int chunkZ, int radius, long time) {
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
        /** Also stores which version of each real chunk the player got */
        private static final int FORMAT_SAVED_CHUNKS = -3;
        /** Also stores the content of those versions */
        private static final int FORMAT_SAVED_CONTENT = -4;

        final Path file;
        final LongOpenHashSet done = new LongOpenHashSet(); // with features; guarded by itself
        final LongOpenHashSet doneTerrain = new LongOpenHashSet(); // terrain only; guarded by done
        /** Real chunks the player got, with the time they were saved then; guarded by done */
        final Long2IntOpenHashMap savedSent = new Long2IntOpenHashMap();
        /** What those real chunks contained ({@link LodChunk#contentHash()}); guarded by done */
        final Long2LongOpenHashMap savedContent = new Long2LongOpenHashMap();
        volatile boolean dirty;
        /** Set by workers to restart the search at the player */
        volatile boolean rescan;
        volatile long pausedUntil;
        // scheduler thread only
        /** The view distance the walks left out, see {@link #queueNext} */
        int viewDistance;
        int centerX;
        int centerZ;
        /** Terrain everywhere; in the single pass also the details up to featureRadius */
        final Cursor terrain = new Cursor();
        /** The details up to featureRadius, when terrain comes first */
        final Cursor details = new Cursor();
        /** Terrain around where the 3D map looks, while it is open */
        final Cursor focus = new Cursor();
        int focusX;
        int focusZ;
        int focusRadius;
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

        /** Whether the player got this real chunk as it was saved at that time, or newer */
        boolean hasSaved(long key, int savedAt) {
            synchronized (this.done) {
                return this.savedSent.get(key) >= savedAt;
            }
        }

        void markSaved(long key, int savedAt, long content) {
            synchronized (this.done) {
                this.savedSent.put(key, savedAt);
                this.savedContent.put(key, content);
            }
            this.dirty = true;
        }

        /** Whether the player already has this content for the real chunk, from an earlier version */
        boolean hasSavedContent(long key, long content) {
            synchronized (this.done) {
                return content != 0 && this.savedContent.get(key) == content;
            }
        }

        int doneCount() {
            synchronized (this.done) {
                return this.done.size() + this.doneTerrain.size() + this.savedSent.size();
            }
        }

        void clear() {
            synchronized (this.done) {
                this.done.clear();
                this.doneTerrain.clear();
                this.savedSent.clear();
                this.savedContent.clear();
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
                if (first == FORMAT_TWO_LEVELS || first == FORMAT_SAVED_CHUNKS || first == FORMAT_SAVED_CONTENT) {
                    readSet(in, this.done, in.readInt());
                    readSet(in, this.doneTerrain, in.readInt());
                    if (first != FORMAT_TWO_LEVELS) {
                        int count = in.readInt();
                        for (int i = 0; i < count; i++) {
                            long key = in.readLong();
                            this.savedSent.put(key, in.readInt());
                            if (first == FORMAT_SAVED_CONTENT) {
                                this.savedContent.put(key, in.readLong());
                            }
                        }
                    }
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
                this.savedSent.clear();
                this.savedContent.clear();
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
                        out.writeInt(FORMAT_SAVED_CONTENT);
                        writeSet(out, this.done);
                        writeSet(out, this.doneTerrain);
                        out.writeInt(this.savedSent.size());
                        for (Long2IntMap.Entry entry : this.savedSent.long2IntEntrySet()) {
                            out.writeLong(entry.getLongKey());
                            out.writeInt(entry.getIntValue());
                            out.writeLong(this.savedContent.get(entry.getLongKey()));
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

    private enum Join { ADDED, ALREADY, TOO_LATE }

    /**
     * A square of chunks to build (a single chunk without features, a tile with them), or a real chunk to read from
     * the save, and everyone it goes to
     */
    private static final class Task {
        final int minX;
        final int minZ;
        final int size;
        final boolean features;
        /** Chunks that exist as real chunks on disk: built for context, but not delivered */
        final boolean[] saved;
        /** For a real chunk: when it was saved, the version this task delivers; 0 for generated LODs */
        final int savedAt;
        private final List<Recipient> recipients = new ArrayList<>(2);
        private boolean delivering;

        Task(int minX, int minZ, int size, boolean features, boolean[] saved, int savedAt) {
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.features = features;
            this.saved = saved;
            this.savedAt = savedAt;
        }

        /** Whether this player still needs the chunk at this index from this task */
        boolean neededBy(int index, Progress progress) {
            long key = this.chunkKey(index);
            return this.savedAt != 0 ? !progress.hasSaved(key, this.savedAt) : !this.saved[index] && !progress.isDone(key, this.features);
        }

        /**
         * @param chunk what was delivered; null when it could not be built
         */
        void delivered(int index, Progress progress, @Nullable LodChunk chunk) {
            if (this.savedAt != 0) {
                progress.markSaved(this.chunkKey(index), this.savedAt, chunk == null ? 0 : chunk.contentHash());
            } else {
                progress.markDone(this.chunkKey(index), this.features);
            }
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
        final Map<Long, Task> real = new ConcurrentHashMap<>(); // real chunks read from the save, by chunk
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
    // What the LOD chunks built so far are: only terrain, with features, or read from real chunks in the save
    private final AtomicLong builtTerrain = new AtomicLong();
    private final AtomicLong builtFeatures = new AtomicLong();
    private final AtomicLong builtSaved = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    /** Workers wake the scheduler through this as soon as they finish a chunk */
    private final Object workerFinished = new Object();
    private volatile List<Viewer> viewers = List.of();
    private final Map<UUID, MapFocus> mapFocus = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile boolean paused;
    /** The server's ticks take longer than tickTimeLimit: only one worker runs until they are fast again */
    private volatile boolean throttled;
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
        this.workers = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(), 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new LodWorkerThread(runnable, "LodGen-Worker-" + threadNumber.incrementAndGet());
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.scheduler = new Thread(this::schedulerLoop, "LodGen-Scheduler");
        this.scheduler.setDaemon(true);
        this.scheduler.start();
        LodGen.LOGGER.info("Generating LODs up to {} chunks away with {} threads ({})", config.radius, config.workerThreads(), config.performance);
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
            // The chunks the game sends this player itself
            int viewDistance = Mth.clamp(player.requestedViewDistance(), 2, this.server.getPlayerList().getViewDistance());
            current.add(new Viewer(player.getUUID(), player.getScoreboardName(), host, (ServerLevel) player.level(), pos.x(), pos.z(), player.getYRot(),
                    viewDistance, player, 0, 0, 0));
        }
        if (current.isEmpty() && this.benchmark) {
            current.add(new Viewer(NO_PLAYER, "benchmark", false, this.server.overworld(), 0, 0, 0, 0, null, 0, 0, 0));
        }
        this.viewers = List.copyOf(current);
    }

    /**
     * The viewer with where their 3D map looks, if it is open. Not done on the server tick: the map pauses a single
     * player game, which stops the ticks but still takes in what the client sends.
     */
    private Viewer withFocus(Viewer viewer, long now) {
        MapFocus focus = this.mapFocus.get(viewer.id);
        if (focus == null || this.config.mapRadius <= 0 || focus.dimension != viewer.level.dimension() || now - focus.time >= FOCUS_TIMEOUT_MILLIS) {
            return viewer;
        }
        // Not further from the player than the server allows
        int dx = focus.chunkX - viewer.chunkX;
        int dz = focus.chunkZ - viewer.chunkZ;
        double scale = Math.min(1.0, this.config.mapRadius / Math.max(1.0, Math.max(Math.abs(dx), Math.abs(dz))));
        return new Viewer(viewer.id, viewer.name, viewer.host, viewer.level, viewer.chunkX, viewer.chunkZ, viewer.yaw, viewer.viewDistance,
                viewer.player, viewer.chunkX + (int) Math.round(dx * scale), viewer.chunkZ + (int) Math.round(dz * scale), focus.radius);
    }

    /**
     * Server thread, once a second: everyone with the 3D map open learns where the other players in their dimension
     * are, also those beyond the LODs. Not while the game is paused, but then nobody else is there anyway.
     */
    void sendMapPlayers() {
        if (this.mapFocus.isEmpty() || !this.config.farPlayers) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            MapFocus focus = this.mapFocus.get(player.getUUID());
            if (focus == null || now - focus.time >= FOCUS_TIMEOUT_MILLIS || !ServerPlayNetworking.canSend(player, MapPlayersPayload.TYPE)) {
                continue;
            }
            List<MapPlayersPayload.Entry> others = new ArrayList<>();
            for (ServerPlayer other : ((ServerLevel) player.level()).players()) {
                if (other != player && !other.isSpectator()) {
                    others.add(new MapPlayersPayload.Entry(other.getUUID(), other.getScoreboardName(), other.getX(), other.getY(), other.getZ()));
                }
            }
            ServerPlayNetworking.send(player, new MapPlayersPayload(player.level().dimension(), others));
        }
    }

    /** Network: where the 3D map of the player looks now, or that it was closed */
    void setMapFocus(ServerPlayer player, MapFocusPayload payload) {
        if (payload.radius() <= 0) {
            this.mapFocus.remove(player.getUUID());
        } else {
            this.mapFocus.put(player.getUUID(), new MapFocus(player.level().dimension(), payload.chunkX(), payload.chunkZ(),
                    Math.min(payload.radius(), MAX_FOCUS_RADIUS), System.currentTimeMillis()));
        }
    }

    boolean isPaused() {
        return this.paused;
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
        StringBuilder text = new StringBuilder(this.paused ? "[paused] " : this.throttled ? "[throttled, the server is busy] " : "");
        text.append(String.format("%.1f chunks/s, %d in progress, %d threads, %d player(s), %d sent over the network",
                this.rate, this.inFlight.get(), this.threads(), current.size(), this.sent.get()));
        text.append(String.format("; built since the start: %d (%d terrain only, %d with features, %d from the save)",
                this.generated.get(), this.builtTerrain.get(), this.builtFeatures.get(), this.builtSaved.get()));
        Viewer viewer = this.statusViewer(asking, current);
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
                if (viewer.focusRadius > 0) {
                    rings += ", map at " + viewer.focusX * 16 + ", " + viewer.focusZ * 16 + " " + ringStatus(progress.focus.shownRing, viewer.focusRadius);
                }
                text.append(String.format("; %s in %s: %s, %d LOD chunks", viewer.name, viewer.level.dimension().identifier(),
                        rings, progress.doneCount()));
            }
        } else if (asking != null) {
            text.append("; you get no LODs (this mod or Voxy is missing on your client)");
        }
        return text.toString();
    }

    /** The same as {@link #status}, laid out over several lines with colours, for the /lodgen command */
    Component statusMessage(@Nullable ServerPlayer asking) {
        List<Viewer> current = this.viewers;
        MutableComponent text = LodChat.tag().append(Component.literal("Status").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        if (this.paused) {
            text.append(Component.literal("  paused").withStyle(ChatFormatting.YELLOW));
        } else if (this.throttled) {
            text.append(Component.literal(String.format("  slowed down: ticks take %.0f ms", this.server.getAverageTickTimeNanos() / 1e6))
                    .withStyle(ChatFormatting.YELLOW));
        }
        text.append(LodChat.newLine()).append(LodChat.label("Speed  "))
                .append(LodChat.value(String.format("%.1f", this.rate))).append(LodChat.label(" chunks/s"))
                .append(LodChat.separator()).append(LodChat.value(this.inFlight.get())).append(LodChat.label(" in progress"))
                .append(LodChat.separator()).append(LodChat.value(this.threads())).append(LodChat.label(" threads"));
        text.append(LodChat.newLine()).append(LodChat.label("Players  ")).append(LodChat.value(current.size()))
                .append(LodChat.separator()).append(LodChat.value(this.sent.get())).append(LodChat.label(" sent over the network"));
        text.append(LodChat.newLine()).append(LodChat.label("Built  ")).append(LodChat.value(this.generated.get()))
                .append(LodChat.label(" (")).append(LodChat.value(this.builtTerrain.get())).append(LodChat.label(" terrain only"))
                .append(LodChat.separator()).append(LodChat.value(this.builtFeatures.get())).append(LodChat.label(" with features"))
                .append(LodChat.separator()).append(LodChat.value(this.builtSaved.get())).append(LodChat.label(" from the save)"));
        Viewer viewer = this.statusViewer(asking, current);
        if (viewer != null) {
            text.append(LodChat.newLine()).append(Component.literal(viewer.name).withStyle(ChatFormatting.AQUA))
                    .append(LodChat.label(" in ")).append(LodChat.value(viewer.level.dimension().identifier().toString()));
            Dimension dimension = this.dimensions.get(viewer.level.dimension());
            if (dimension != null && dimension.builder == null) {
                text.append(LodChat.newLine()).append(Component.literal("  This dimension is not supported").withStyle(ChatFormatting.RED));
            } else {
                Progress progress = this.progressOf(viewer);
                text.append(LodChat.newLine()).append(LodChat.label(this.config.terrainFirst ? "  Terrain  " : "  Rings  "))
                        .append(LodChat.progress(progress.terrain.shownRing, this.config.radius));
                if (this.config.terrainFirst) {
                    text.append(LodChat.newLine()).append(LodChat.label("  Details  "))
                            .append(LodChat.progress(progress.details.shownRing, this.config.featureRadius));
                }
                if (viewer.focusRadius > 0) {
                    text.append(LodChat.newLine()).append(LodChat.label("  3D map  "))
                            .append(LodChat.progress(progress.focus.shownRing, viewer.focusRadius))
                            .append(LodChat.label(" at ")).append(LodChat.value(viewer.focusX * 16 + ", " + viewer.focusZ * 16));
                }
                text.append(LodChat.newLine()).append(LodChat.label("  LOD chunks  ")).append(LodChat.value(progress.doneCount()));
            }
        } else if (asking != null) {
            text.append(LodChat.newLine()).append(Component.literal("You get no LODs: this mod or Voxy is missing on your client")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return text;
    }

    /** Whose progress the status shows: the asking player's, or the only player's when the console asks */
    private @Nullable Viewer statusViewer(@Nullable ServerPlayer asking, List<Viewer> current) {
        Viewer viewer = asking == null ? null : this.viewerOf(asking.getUUID());
        if (viewer == null && current.size() == 1) {
            viewer = current.getFirst();
        }
        return viewer == null ? null : this.withFocus(viewer, System.currentTimeMillis());
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
        // {time, chunks generated by then}, oldest first
        ArrayDeque<long[]> samples = new ArrayDeque<>();
        samples.add(new long[] {lastSave, 0});
        while (this.running) {
            try {
                boolean full = false;
                this.updateThrottle();
                this.adjustThreads();
                if (!this.paused) {
                    full = this.fillQueue();
                }

                long now = System.currentTimeMillis();
                if (now - lastStats >= STATS_INTERVAL_MILLIS) {
                    long total = this.generated.get();
                    samples.add(new long[] {now, total});
                    while (now - samples.getFirst()[0] > RATE_WINDOW_MILLIS) {
                        samples.removeFirst();
                    }
                    long[] oldest = samples.getFirst();
                    this.rate = now == oldest[0] ? 0 : (total - oldest[1]) * 1000.0 / (now - oldest[0]);
                    if (total != generatedAtLastStats) {
                        LodGen.LOGGER.info("{}", this.status(null));
                    }
                    generatedAtLastStats = total;
                    lastStats = now;
                }
                if (now - lastSave >= SAVE_INTERVAL_MILLIS) {
                    this.saveAll();
                    lastSave = now;
                    if (this.config.sendSavedChunks) {
                        // Real chunks change when players build: walk again to send the ones saved since
                        this.progress.values().forEach(byDimension -> byDimension.values().forEach(progress -> progress.rescan = true));
                    }
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
        for (Viewer published : current) {
            Viewer viewer = this.withFocus(published, now);
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
        if (this.server.isPaused()) {
            return this.config.workerThreadsWhilePaused();
        }
        return this.throttled ? 1 : this.config.workerThreads();
    }

    /**
     * Slows down to one worker while the server's ticks (averaged over the last 5 seconds) take longer than
     * tickTimeLimit, and speeds up again once they are well below it, so it does not flip back and forth. A paused
     * game does not tick.
     */
    private void updateThrottle() {
        int limit = this.config.tickTimeLimit;
        if (limit <= 0 || this.server.isPaused()) {
            this.throttled = false;
            return;
        }
        double millis = this.server.getAverageTickTimeNanos() / 1e6;
        if (millis > limit && !this.throttled) {
            this.throttled = true;
            LodGen.LOGGER.info("Ticks take {} ms, more than {} ms: slowing down to one thread", Math.round(millis), limit);
        } else if (millis < limit * 0.75 && this.throttled) {
            this.throttled = false;
            LodGen.LOGGER.info("Ticks take {} ms again: back to {} threads", Math.round(millis), this.config.workerThreads());
        }
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
        int viewDistance = viewer.viewDistance == 0 ? 0 : viewer.viewDistance + this.config.viewDistanceMargin;
        // The game loads a circle: the first ring that reaches beyond it is the one through its diagonal
        int minRing = Math.max(1, (int) (viewDistance / Math.sqrt(2)));
        if (progress.terrain.ring < 0 || progress.rescan || progress.viewDistance != viewDistance
                || Math.max(Math.abs(viewer.chunkX - progress.centerX), Math.abs(viewer.chunkZ - progress.centerZ)) >= RESCAN_DISTANCE) {
            progress.centerX = viewer.chunkX;
            progress.centerZ = viewer.chunkZ;
            progress.viewDistance = viewDistance;
            progress.terrain.start(minRing, viewer.yaw);
            progress.details.start(minRing, viewer.yaw);
            progress.focus.ring = -1;
            progress.rescan = false;
        }
        if (viewer.focusRadius > 0) {
            // The 3D map is open: first the terrain around where it looks, nearest first
            if (progress.focus.ring < 0 || progress.focusRadius != viewer.focusRadius
                    || Math.max(Math.abs(viewer.focusX - progress.focusX), Math.abs(viewer.focusZ - progress.focusZ)) >= FOCUS_MOVE) {
                progress.focusX = viewer.focusX;
                progress.focusZ = viewer.focusZ;
                progress.focusRadius = viewer.focusRadius;
                progress.focus.start(0, viewer.yaw);
            }
            if (progress.focus.ring <= progress.focusRadius) {
                if (this.walk(viewer, progress, progress.focus, progress.focusX, progress.focusZ, progress.focusRadius, false)) {
                    return true;
                }
                if (progress.focus.ring <= progress.focusRadius) {
                    return false;
                }
            }
        } else {
            progress.focus.ring = -1;
            progress.focus.shownRing = -1;
        }
        if (!this.config.terrainFirst) {
            return this.walk(viewer, progress, progress.terrain, progress.centerX, progress.centerZ, this.config.radius, false);
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
            if (this.walk(viewer, progress, cursor, progress.centerX, progress.centerZ, limit, details)) {
                return true;
            }
            if (cursor.ring <= limit) {
                // Waiting (send rate, Voxy not ready, a tile being delivered): try again later
                return false;
            }
        }
    }

    /**
     * Walks square rings around the player (or where their 3D map looks), nearest first and starting where they look,
     * to the next chunk they still need.
     *
     * @param onlyDetails every chunk on this walk gets details; otherwise only up to featureRadius in a single pass
     * @return whether a chunk was queued for them
     */
    private boolean walk(Viewer viewer, Progress progress, Cursor cursor, int centerX, int centerZ, int limit, boolean onlyDetails) {
        Dimension dimension = this.dimensions.get(viewer.level.dimension());
        long radiusSquared = (long) limit * limit;
        while (cursor.ring <= limit) {
            int ring = cursor.ring;
            int index = cursor.ringIndex;
            cursor.shownRing = ring;
            // Ring 0 is the center chunk alone
            int ringSize = Math.max(1, 8 * ring);
            if (index >= ringSize) {
                cursor.start(cursor.ring + 1, viewer.yaw);
                continue;
            }
            cursor.ringIndex++;
            int dx = 0;
            int dz = 0;
            if (ring > 0) {
                // From where the player looks, alternately one step further to each side
                int step = (index + 1) / 2;
                index = Math.floorMod(cursor.ringStart + (index % 2 == 1 ? step : -step), ringSize);
                int side = index / (2 * ring);
                int offset = index % (2 * ring);
                switch (side) {
                    case 0 -> { dx = -ring + offset; dz = -ring; }
                    case 1 -> { dx = ring; dz = -ring + offset; }
                    case 2 -> { dx = ring - offset; dz = ring; }
                    default -> { dx = -ring; dz = ring - offset; }
                }
            }
            int chunkX = centerX + dx;
            int chunkZ = centerZ + dz;
            if ((long) dx * dx + (long) dz * dz > radiusSquared || progress.viewDistance > 0
                    && ChunkTrackingView.isWithinDistance(progress.centerX, progress.centerZ, progress.viewDistance, chunkX, chunkZ, false)) {
                // Beyond the radius, or a chunk the game shows itself
                continue;
            }
            long key = pack(chunkX, chunkZ);
            // Around the 3D map only terrain: it is about seeing far quickly, the details follow near the player
            boolean features = onlyDetails || cursor != progress.focus && !this.config.terrainFirst && ring <= this.config.featureRadius;
            int savedAt = dimension.saved.savedAt(chunkX, chunkZ);
            if (savedAt != 0) {
                // A real chunk: its LOD comes from the save, with the players' builds, never from generated terrain.
                // Sent once per version, by the walk that covers the whole radius
                if (!this.config.sendSavedChunks || cursor == progress.details || progress.hasSaved(key, savedAt)) {
                    continue;
                }
                features = false;
            } else if (progress.isDone(key, features)) {
                continue;
            }

            LodSink sink = viewer.host ? dimension.hostSink()
                    : viewer.player == null ? LodSink.DISCARD : new NetworkSink(viewer.player, viewer.level.dimension());
            if (sink == null) {
                cursor.ringIndex--;
                return false;
            }
            Recipient recipient = new Recipient(sink, progress);

            // Features are built in tiles, terrain and real chunks chunk by chunk
            int size = features ? this.config.tileSize : 1;
            int minX = Math.floorDiv(chunkX, size) * size;
            int minZ = Math.floorDiv(chunkZ, size) * size;
            Map<Long, Task> tasks = savedAt != 0 ? dimension.real : features ? dimension.tiles : dimension.queued;
            long taskKey = pack(minX, minZ);

            // Already being built for someone else: add this player to it
            Task task = tasks.get(taskKey);
            if (task == null) {
                boolean[] saved = new boolean[size * size];
                for (int i = 0; savedAt == 0 && i < saved.length; i++) {
                    saved[i] = dimension.saved.exists(minX + i / size, minZ + i % size);
                }
                task = new Task(minX, minZ, size, features, saved, savedAt);
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
            if (task.neededBy(index, progress)) {
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
                if (task.savedAt != 0) {
                    LodChunk chunk = dimension.builder.fromSaved(new ChunkPos(task.minX, task.minZ));
                    chunks = chunk == null ? null : List.of(chunk);
                } else {
                    chunks = task.features
                            ? dimension.builder.buildTile(task.minX, task.minZ, task.size, () -> !this.running)
                            : List.of(dimension.builder.build(new ChunkPos(task.minX, task.minZ)));
                }
                if (chunks != null) {
                    this.generated.addAndGet(chunks.size());
                    (task.savedAt != 0 ? this.builtSaved : task.features ? this.builtFeatures : this.builtTerrain).addAndGet(chunks.size());
                }
            } catch (Throwable t) {
                if (!this.running) {
                    // The world is closing (and workers get interrupted): nothing is lost, it is built next time
                    return;
                }
                if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LodGen.LOGGER.error("Could not build the LOD for chunk {}, {}", task.minX, task.minZ, t);
                }
            }
            (task.savedAt != 0 ? dimension.real : task.features ? dimension.tiles : dimension.queued).remove(taskKey);
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
            if (!task.neededBy(index, recipient.progress)) {
                continue;
            }
            if (chunks == null) {
                // Skip it instead of failing on it forever
                task.delivered(index, recipient.progress, null);
                continue;
            }
            LodChunk chunk = chunks.get(index);
            try {
                // The server saves loaded chunks again and again, mostly without changes: those need not be sent
                if (task.savedAt != 0 && recipient.progress.hasSavedContent(task.chunkKey(index), chunk.contentHash())) {
                    task.delivered(index, recipient.progress, chunk);
                    continue;
                }
                if (!recipient.sink.accept(chunk)) {
                    // Not ready (Voxy still loading, player left or switched dimension): try again later
                    recipient.progress.pausedUntil = System.currentTimeMillis() + RETRY_MILLIS;
                    recipient.progress.rescan = true;
                    return;
                }
                task.delivered(index, recipient.progress, chunk);
                if (recipient.sink instanceof NetworkSink) {
                    this.sent.incrementAndGet();
                }
            } catch (Throwable t) {
                task.delivered(index, recipient.progress, null);
                if (this.errors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LodGen.LOGGER.error("Could not deliver the LOD for chunk {}, {}", task.minX + index / task.size, task.minZ + index % task.size, t);
                }
            }
        }
    }
}
