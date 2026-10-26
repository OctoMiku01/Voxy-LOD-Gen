package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Fills the horizon beyond the radius: square rings around each player who gets LODs, from just outside the radius up
 * to horizonRadius. Rough LODs where nothing exists yet, real ones read from the save where chunks were explored;
 * chunks the player already got are left alone, and real LODs replace the rough ones whenever they come. The further a
 * ring, the coarser its samples. Runs on threads of its own, pausing with the generation, and only once the real LODs
 * are done.
 * <p>
 * The rough LODs are made of {@link HorizonSamples}, taken once per world and saved ({@link HorizonStore}). The host
 * gets the LODs built from them straight into Voxy; other players get the samples, a few dozen bytes per chunk, and
 * their client builds the LODs. Which chunks each player got is saved per dimension in {@code <world>/lodgen/horizon/}.
 */
final class LodHorizon {
    /** A player moving this many chunks away starts their rings again around where they are */
    private static final int RECENTER_DISTANCE = 32;
    private static final long STATS_INTERVAL_MILLIS = 30_000;
    /** Rough LODs are built far faster than Voxy takes them in: above this many waiting sections, the host waits */
    private static final int MAX_VOXY_BACKLOG = 10_000;
    /** Ground heights at grid corners kept for the neighbouring chunks, which share them; a few rings' worth */
    private static final int HEIGHT_CACHE = 200_000;
    /** Raised when rough LODs are built differently: files of an older format are dropped, so all is built again */
    private static final int FORMAT = 1;
    private static final long SAVE_INTERVAL_MILLIS = 60_000;
    /** Samples sent to a player wait at most this long to go out with more */
    private static final long BATCH_MILLIS = 1_000;
    /** A real LOD read from the save is about this many times the size of a chunk's samples, for the send rate */
    private static final int REAL_COST = 50;

    /** Someone who gets the horizon: the host (straight into Voxy) or a player with this mod (over the network) */
    record Target(UUID id, boolean host, ServerLevel level, int chunkX, int chunkZ, @Nullable ServerPlayer player) {
    }

    /**
     * For /lodgen: the ring it is on (-1 before it started in that dimension, beyond toRing once done), how many
     * chunks of the dimension the player got, and why it waits, if it does
     */
    record Status(int ring, int toRing, int done, double rate, @Nullable String waiting) {
    }

    /** One player's horizon in one dimension */
    private static final class Watcher {
        final ResourceKey<Level> dimension;
        final Path file;
        /** Chunks the player got; guarded by the horizon */
        final LongOpenHashSet done;
        boolean unsaved;
        boolean started;
        int centerX;
        int centerZ;
        int ring;
        int index;
        double tokens;
        long lastRefill;
        /** Only the host; created when they are first served */
        @Nullable LodSink sink;
        // Samples waiting to be sent, guarded by the batch
        final List<LodHorizonPayload.Chunk> batch = new ArrayList<>();
        final LongArrayList batchKeys = new LongArrayList();
        long batchSince;

        Watcher(ResourceKey<Level> dimension, Path file) {
            this.dimension = dimension;
            this.file = file;
            this.done = load(file);
        }
    }

    /** One chunk to do, with what it needs */
    private record Job(Watcher watcher, Target target, int x, int z, int step, boolean explored) {
    }

    private final LodGenerator generator;
    private final LodConfig config;
    private final int fromRing;
    private final int radius;
    private final int toRing;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;
    private final AtomicLong built = new AtomicLong();

    // Guarded by this
    private final Map<UUID, Map<ResourceKey<Level>, Watcher>> watchers = new HashMap<>();
    /** Who gets the horizon next, so all players move on at the same pace */
    private int turn;
    private @Nullable String lastWaiting;
    private long lastStats = System.currentTimeMillis();
    private long builtAtLastStats;
    private long lastSave = System.currentTimeMillis();
    /** Chunks per second over the last few seconds, see {@link #updateRate} */
    private double rate;
    private long rateTime = System.currentTimeMillis();
    private long rateBuilt;

    private final Map<ResourceKey<Level>, SavedChunks> saved = new HashMap<>();
    /** Per dimension, shared by all threads, see {@link LodHorizonSampler} */
    private final Map<ResourceKey<Level>, ChunkCache<Integer>> heights = new HashMap<>();
    private final Map<ResourceKey<Level>, HorizonStore> stores = new ConcurrentHashMap<>();
    // Builders and samplers are per thread, see run

    LodHorizon(LodGenerator generator, LodConfig config) {
        this.generator = generator;
        this.config = config;
        // The generation fills a circle of the radius on square rings: the horizon starts where the circle leaves the
        // rings, so the corners between them are filled too, and leaves the circle to the generation
        this.fromRing = (int) (config.radius / Math.sqrt(2));
        this.radius = config.radius;
        this.toRing = config.horizonRadius;
        // Half of the generation's threads: the real LODs around the players come first
        int count = Math.max(1, config.workerThreads() / 2);
        for (int i = 1; i <= count; i++) {
            Thread thread = new LodWorkerThread(this::run, "LodGen-Horizon-" + i);
            thread.setPriority(Thread.MIN_PRIORITY);
            this.threads.add(thread);
            thread.start();
        }
        LodGen.LOGGER.info("Horizon from {} up to {} chunks away with {} threads", this.radius, this.toRing, count);
    }

    void stop() {
        this.running = false;
        for (Thread thread : this.threads) {
            thread.interrupt();
        }
        for (Thread thread : this.threads) {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Samples still waiting to be sent are not marked as done: they are sent next time
        this.save();
    }

    synchronized Status status(Target target) {
        Watcher watcher = this.watcherOf(target);
        int ring = watcher.started ? watcher.ring : -1;
        this.updateRate();
        return new Status(ring, this.toRing, watcher.done.size(), this.rate, ring > this.toRing ? null : this.lastWaiting);
    }

    /** Forgets what this player got in this dimension, e.g. after Voxy's data was deleted: they get it again */
    synchronized void clear(Target target) {
        Watcher watcher = this.watcherOf(target);
        watcher.done.clear();
        watcher.unsaved = true;
        // Start the rings again
        watcher.started = false;
    }

    private void run() {
        // Per dimension and thread: they keep scratch state
        Map<ResourceKey<Level>, LodHorizonSampler> samplers = new HashMap<>();
        Map<ResourceKey<Level>, LodHorizonBuilder> rough = new HashMap<>();
        Map<ResourceKey<Level>, LodChunkBuilder> real = new HashMap<>();
        try {
            while (this.running) {
                @Nullable Job job = this.next();
                if (job == null) {
                    Thread.sleep(200);
                    continue;
                }
                ServerLevel level = job.target.level();
                ResourceKey<Level> key = level.dimension();
                long chunk = LodGenerator.pack(job.x, job.z);
                if (job.explored) {
                    LodChunk lod = real.computeIfAbsent(key, k -> LodChunkBuilder.create(level, this.config.structures, this.config.keepUnderground))
                            .fromSaved(new ChunkPos(job.x, job.z));
                    if (lod != null && this.deliver(job, lod)) {
                        this.markDone(job.watcher, chunk);
                    }
                } else {
                    HorizonStore store = this.storeOf(level);
                    HorizonSamples samples = store.get(job.x, job.z, job.step);
                    if (samples == null) {
                        samples = samplers.computeIfAbsent(key, k -> new LodHorizonSampler(level, this.heightsOf(key))).sample(job.x, job.z, job.step);
                        store.put(job.x, job.z, samples);
                    }
                    if (job.target.host()) {
                        LodChunk lod = rough.computeIfAbsent(key, k -> new LodHorizonBuilder(level)).build(job.x, job.z, samples);
                        if (this.deliver(job, lod)) {
                            this.markDone(job.watcher, chunk);
                        }
                    } else {
                        this.addToBatch(job, chunk, samples);
                    }
                }
                this.built.incrementAndGet();
            }
        } catch (InterruptedException e) {
            // Stopped
        } catch (Throwable t) {
            LodGen.LOGGER.error("The horizon stopped", t);
        }
    }

    /** @return whether the LOD reached the player; if not, it is done again later */
    private boolean deliver(Job job, LodChunk lod) throws InterruptedException {
        if (!job.target.host()) {
            ServerPlayer player = job.target.player();
            return player != null && new NetworkSink(player, job.watcher.dimension).accept(lod);
        }
        while (this.running) {
            if (job.watcher.sink.accept(lod)) {
                return true;
            }
            // Voxy is not ready yet, e.g. right after joining
            Thread.sleep(500);
        }
        return false;
    }

    private synchronized void markDone(Watcher watcher, long chunk) {
        watcher.done.add(chunk);
        watcher.unsaved = true;
    }

    private void addToBatch(Job job, long chunk, HorizonSamples samples) {
        Watcher watcher = job.watcher;
        synchronized (watcher.batch) {
            if (watcher.batch.isEmpty()) {
                watcher.batchSince = System.currentTimeMillis();
            }
            watcher.batch.add(new LodHorizonPayload.Chunk(job.x, job.z, samples));
            watcher.batchKeys.add(chunk);
            if (watcher.batch.size() >= LodHorizonPayload.MAX_CHUNKS) {
                this.flush(watcher, job.target.player());
            }
        }
    }

    /** Sends the samples waiting for this player; if they left or changed dimension, they get them next time */
    private void flush(Watcher watcher, @Nullable ServerPlayer player) {
        synchronized (watcher.batch) {
            if (watcher.batch.isEmpty()) {
                return;
            }
            if (player != null && !player.hasDisconnected() && player.level().dimension() == watcher.dimension) {
                ServerPlayNetworking.send(player, new LodHorizonPayload(watcher.dimension, List.copyOf(watcher.batch)));
                synchronized (this) {
                    watcher.done.addAll(watcher.batchKeys);
                    watcher.unsaved = true;
                }
            }
            watcher.batch.clear();
            watcher.batchKeys.clear();
        }
    }

    /** The next chunk to do, or null while there is none (no players, paused, slowed down, or all done) */
    private @Nullable Job next() {
        List<Target> targets = this.generator.horizonTargets();
        // Outside the lock on the horizon: flushing takes the batch's lock, and markDone takes this one inside it
        this.flushOld(targets);
        synchronized (this) {
            this.logStats();
            this.updateRate();
            if (System.currentTimeMillis() - this.lastSave >= SAVE_INTERVAL_MILLIS) {
                this.save();
            }
            String waiting = targets.isEmpty() ? "no players" : this.generator.isPaused() ? "paused" : this.generator.isThrottled() ? "slowed down"
                    : !this.generator.idle() ? "real LODs first" : null;
            if (waiting != null) {
                if (!waiting.equals(this.lastWaiting)) {
                    LodGen.LOGGER.info("Horizon waits: {}", waiting);
                    this.lastWaiting = waiting;
                }
                return null;
            }
            this.lastWaiting = null;
            long now = System.currentTimeMillis();
            for (int k = 0; k < targets.size(); k++) {
                Target target = targets.get((this.turn + k) % targets.size());
                @Nullable Job job = this.nextFor(target, now);
                if (job != null) {
                    this.turn = (this.turn + k + 1) % targets.size();
                    return job;
                }
            }
            return null;
        }
    }

    private void flushOld(List<Target> targets) {
        long now = System.currentTimeMillis();
        for (Target target : targets) {
            if (target.host()) {
                continue;
            }
            Watcher watcher;
            synchronized (this) {
                watcher = this.watcherOf(target);
            }
            boolean old;
            synchronized (watcher.batch) {
                old = !watcher.batch.isEmpty() && now - watcher.batchSince >= BATCH_MILLIS;
            }
            if (old) {
                this.flush(watcher, target.player());
            }
        }
    }

    // Guarded by this
    private @Nullable Job nextFor(Target target, long now) {
        Watcher watcher = this.watcherOf(target);
        if (target.host()) {
            if (VoxySink.backlog() > MAX_VOXY_BACKLOG) {
                return null;
            }
            if (watcher.sink == null) {
                watcher.sink = VoxySink.create(target.level());
                if (watcher.sink == null) {
                    return null;
                }
            }
        } else {
            // Protects the player's connection, like the real LODs
            double limit = this.config.maxHorizonChunksPerSecondPerPlayer;
            watcher.tokens = watcher.lastRefill == 0 ? limit : Math.min(limit, watcher.tokens + (now - watcher.lastRefill) * limit / 1000.0);
            watcher.lastRefill = now;
            if (watcher.tokens < 1) {
                return null;
            }
        }
        if (!watcher.started || Math.max(Math.abs(target.chunkX() - watcher.centerX), Math.abs(target.chunkZ() - watcher.centerZ)) >= RECENTER_DISTANCE) {
            watcher.started = true;
            watcher.centerX = target.chunkX();
            watcher.centerZ = target.chunkZ();
            watcher.ring = this.fromRing;
            watcher.index = 0;
        }
        while (watcher.ring <= this.toRing) {
            // Square ring: 8 * ring chunks, starting at its north-west corner
            int side = 2 * watcher.ring;
            int i = watcher.index;
            int x;
            int z;
            if (i < side) {
                x = watcher.centerX - watcher.ring + i;
                z = watcher.centerZ - watcher.ring;
            } else if (i < 2 * side) {
                x = watcher.centerX + watcher.ring;
                z = watcher.centerZ - watcher.ring + i - side;
            } else if (i < 3 * side) {
                x = watcher.centerX + watcher.ring - (i - 2 * side);
                z = watcher.centerZ + watcher.ring;
            } else {
                x = watcher.centerX - watcher.ring;
                z = watcher.centerZ + watcher.ring - (i - 3 * side);
            }
            if (++watcher.index >= 4 * side) {
                watcher.index = 0;
                watcher.ring++;
            }
            long dx = x - watcher.centerX;
            long dz = z - watcher.centerZ;
            long distanceSquared = dx * dx + dz * dz;
            long chunk = LodGenerator.pack(x, z);
            if (distanceSquared <= (long) this.radius * this.radius || watcher.done.contains(chunk) || this.generator.hasReal(target, chunk)) {
                continue;
            }
            // Finer close to the radius, where the rough LODs meet the real ones; Voxy shows coarser detail further away
            long radius = Math.max(1, this.radius);
            int step = distanceSquared <= 4 * radius * radius ? 4 : distanceSquared <= 16 * radius * radius ? 8 : 16;
            SavedChunks saved = this.savedOf(target.level());
            boolean explored;
            synchronized (saved) {
                explored = saved.exists(x, z);
            }
            if (!target.host()) {
                watcher.tokens -= explored ? REAL_COST : 1;
            }
            return new Job(watcher, target, x, z, step, explored);
        }
        return null;
    }

    // Every few seconds; the generation's speed only counts real LODs, this one the horizon's
    private void updateRate() {
        long now = System.currentTimeMillis();
        if (now - this.rateTime >= 5_000) {
            long total = this.built.get();
            double latest = (total - this.rateBuilt) * 1000.0 / (now - this.rateTime);
            this.rate = this.rate == 0 ? latest : (this.rate + latest) / 2;
            this.rateTime = now;
            this.rateBuilt = total;
        }
    }

    private void logStats() {
        long now = System.currentTimeMillis();
        if (now - this.lastStats < STATS_INTERVAL_MILLIS) {
            return;
        }
        long total = this.built.get();
        if (total != this.builtAtLastStats) {
            StringBuilder rings = new StringBuilder();
            for (Map<ResourceKey<Level>, Watcher> byDimension : this.watchers.values()) {
                for (Watcher watcher : byDimension.values()) {
                    if (watcher.started && watcher.ring <= this.toRing) {
                        rings.append(rings.isEmpty() ? "" : ", ").append(watcher.ring);
                    }
                }
            }
            LodGen.LOGGER.info("Horizon: {} LODs, {} per second, ring {} of {}{}", total, (total - this.builtAtLastStats) * 1000 / (now - this.lastStats),
                    rings.isEmpty() ? "-" : rings, this.toRing, this.generator.localVoxy() ? ", " + VoxySink.backlog() + " sections waiting for Voxy" : "");
        }
        this.lastStats = now;
        this.builtAtLastStats = total;
    }

    // Guarded by this
    private Watcher watcherOf(Target target) {
        return this.watchers.computeIfAbsent(target.id(), id -> new HashMap<>()).computeIfAbsent(target.level().dimension(), key -> {
            Identifier id = key.identifier();
            Path root = target.level().getServer().getWorldPath(LevelResource.ROOT).resolve("lodgen").resolve("horizon");
            // The host's file stays where it was before other players got the horizon
            Path folder = target.host() ? root : root.resolve("players").resolve(target.id().toString());
            return new Watcher(key, folder.resolve(id.getNamespace()).resolve(id.getPath() + ".bin"));
        });
    }

    private static LongOpenHashSet load(Path file) {
        LongOpenHashSet set = new LongOpenHashSet();
        if (!Files.exists(file)) {
            return set;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != FORMAT) {
                // Built differently before: build again
                return set;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                set.add(in.readLong());
            }
        } catch (IOException e) {
            LodGen.LOGGER.warn("Could not read {}, the horizon is built again", file, e);
            set.clear();
        }
        return set;
    }

    private synchronized void save() {
        this.lastSave = System.currentTimeMillis();
        for (Map<ResourceKey<Level>, Watcher> byDimension : this.watchers.values()) {
            for (Watcher watcher : byDimension.values()) {
                if (watcher.unsaved) {
                    save(watcher.file, watcher.done);
                    watcher.unsaved = false;
                }
            }
        }
        for (HorizonStore store : this.stores.values()) {
            store.save();
        }
    }

    private static void save(Path file, LongOpenHashSet set) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(FORMAT);
                out.writeInt(set.size());
                for (LongIterator it = set.iterator(); it.hasNext(); ) {
                    out.writeLong(it.nextLong());
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not save {}", file, e);
        }
    }

    private ChunkCache<Integer> heightsOf(ResourceKey<Level> dimension) {
        synchronized (this.heights) {
            return this.heights.computeIfAbsent(dimension, k -> new ChunkCache<>(HEIGHT_CACHE));
        }
    }

    private HorizonStore storeOf(ServerLevel level) {
        return this.stores.computeIfAbsent(level.dimension(), key -> {
            Identifier id = key.identifier();
            Path folder = level.getServer().getWorldPath(LevelResource.ROOT).resolve("lodgen").resolve("samples")
                    .resolve(id.getNamespace()).resolve(id.getPath());
            // The regions the rings pass through stay in memory: a ring of radius r touches about r / 4 of them
            return new HorizonStore(level, folder, Math.max(64, this.toRing / 2));
        });
    }

    private SavedChunks savedOf(ServerLevel level) {
        synchronized (this.saved) {
            return this.saved.computeIfAbsent(level.dimension(), k -> {
                Path root = level.getServer().getWorldPath(LevelResource.ROOT);
                return new SavedChunks(DimensionType.getStorageFolder(level.dimension(), root).resolve("region"));
            });
        }
    }
}
