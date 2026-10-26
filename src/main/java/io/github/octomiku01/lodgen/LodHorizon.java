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
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Fills the horizon beyond the radius for the player hosting a single player world: square rings around them, from
 * just outside the radius up to horizonRadius. Rough LODs (see {@link LodHorizonBuilder}) where nothing exists yet,
 * real ones read from the save where chunks were explored; chunks the player already got are left alone, and real
 * LODs replace the rough ones whenever they come. The further a ring, the coarser its samples. Runs on threads of its
 * own, pausing with the generation, and only once the host has all real LODs around them. Which chunks it did is saved
 * per dimension in {@code <world>/lodgen/horizon/}.
 */
final class LodHorizon {
    /** The host moving this many chunks away starts the rings again around where they are */
    private static final int RECENTER_DISTANCE = 32;
    private static final long STATS_INTERVAL_MILLIS = 30_000;
    /** Rough LODs are built far faster than Voxy takes them in: above this many waiting sections, the horizon waits */
    private static final int MAX_VOXY_BACKLOG = 10_000;
    /** Ground heights at grid corners kept for the neighbouring chunks, which share them; a few rings' worth */
    private static final int HEIGHT_CACHE = 200_000;
    /** Raised when rough LODs are built differently: files of an older format are dropped, so all is built again */
    private static final int FORMAT = 1;
    private static final long SAVE_INTERVAL_MILLIS = 60_000;

    /** Where the host is, published by the generator */
    record Spot(ServerLevel level, int chunkX, int chunkZ) {
    }

    /**
     * For /lodgen: the ring it is on (-1 before it started in that dimension, beyond toRing once done), how many
     * chunks of the dimension it did, and why it waits, if it does
     */
    record Status(int ring, int toRing, int done, double rate, @Nullable String waiting) {
    }

    /** One chunk to do, with what it needs */
    private record Job(ServerLevel level, int x, int z, int step, LongOpenHashSet done, LodSink sink) {
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
    private @Nullable ResourceKey<Level> dimension;
    private @Nullable LodSink sink;
    private int centerX;
    private int centerZ;
    private int ring;
    private int index;
    private @Nullable String lastWaiting;
    private long lastStats = System.currentTimeMillis();
    private long builtAtLastStats;
    /** Per dimension: chunks done, loaded from and saved to the world */
    private final Map<ResourceKey<Level>, LongOpenHashSet> done = new HashMap<>();
    private final Set<ResourceKey<Level>> unsaved = new HashSet<>();
    private final Map<ResourceKey<Level>, Path> files = new HashMap<>();
    private long lastSave = System.currentTimeMillis();
    /** Chunks per second over the last few seconds, see {@link #updateRate} */
    private double rate;
    private long rateTime = System.currentTimeMillis();
    private long rateBuilt;
    private final Map<ResourceKey<Level>, SavedChunks> saved = new HashMap<>();
    /** Per dimension, shared by all threads, see {@link LodHorizonBuilder} */
    private final Map<ResourceKey<Level>, ChunkCache<Integer>> heights = new HashMap<>();
    // Builders are per thread, see run

    LodHorizon(LodGenerator generator, LodConfig config) {
        this.generator = generator;
        this.config = config;
        // The generation fills a circle of the radius on square rings: the horizon starts where the circle leaves the
        // rings, so the corners between them are filled too, and leaves the circle to the generation
        this.fromRing = (int) (config.radius / Math.sqrt(2));
        this.radius = config.radius;
        this.toRing = config.horizonRadius;
        this.ring = this.fromRing;
        // Half of the generation's threads: the real LODs around the player come first
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
        this.save();
    }

    synchronized Status status(ServerLevel level) {
        boolean here = level.dimension().equals(this.dimension);
        int ring = here ? this.ring : -1;
        this.updateRate();
        return new Status(ring, this.toRing, this.doneOf(level).size(), this.rate, ring > this.toRing ? null : this.lastWaiting);
    }

    /** Forgets what was done in this dimension, e.g. after Voxy's data was deleted: it is built again */
    synchronized void clear(ServerLevel level) {
        this.doneOf(level).clear();
        this.unsaved.add(level.dimension());
        // Start the rings again
        this.dimension = null;
    }

    private void run() {
        // A builder per dimension and thread: they keep scratch state
        Map<ResourceKey<Level>, LodHorizonBuilder> rough = new HashMap<>();
        Map<ResourceKey<Level>, LodChunkBuilder> real = new HashMap<>();
        try {
            while (this.running) {
                if (VoxySink.backlog() > MAX_VOXY_BACKLOG) {
                    Thread.sleep(50);
                    continue;
                }
                @Nullable Job job = this.next();
                if (job == null) {
                    Thread.sleep(1_000);
                    continue;
                }
                ResourceKey<Level> key = job.level.dimension();
                @Nullable LodChunk lod;
                SavedChunks saved = this.savedOf(job.level);
                boolean explored;
                synchronized (saved) {
                    explored = saved.exists(job.x, job.z);
                }
                if (explored) {
                    lod = real.computeIfAbsent(key, k -> LodChunkBuilder.create(job.level, this.config.structures, this.config.keepUnderground))
                            .fromSaved(new ChunkPos(job.x, job.z));
                } else {
                    lod = rough.computeIfAbsent(key, k -> new LodHorizonBuilder(job.level, this.heightsOf(key))).build(job.x, job.z, job.step);
                }
                while (lod != null && this.running && !job.sink.accept(lod)) {
                    // Voxy is not ready yet, e.g. right after joining
                    Thread.sleep(500);
                }
                synchronized (this) {
                    job.done.add(LodGenerator.pack(job.x, job.z));
                    this.unsaved.add(key);
                }
                this.built.incrementAndGet();
            }
        } catch (InterruptedException e) {
            // Stopped
        } catch (Throwable t) {
            LodGen.LOGGER.error("The horizon stopped", t);
        }
    }

    /** The next chunk to do, or null while there is none (no host, paused, slowed down, or all done) */
    private synchronized @Nullable Job next() {
        this.logStats();
        this.updateRate();
        if (System.currentTimeMillis() - this.lastSave >= SAVE_INTERVAL_MILLIS) {
            this.save();
        }
        @Nullable Spot spot = this.generator.hostSpot();
        String waiting = spot == null ? "no host" : this.generator.isPaused() ? "paused" : this.generator.isThrottled() ? "slowed down"
                : !this.generator.idle() ? "real LODs first" : null;
        if (waiting != null) {
            if (!waiting.equals(this.lastWaiting)) {
                LodGen.LOGGER.info("Horizon waits: {}", waiting);
                this.lastWaiting = waiting;
            }
            return null;
        }
        this.lastWaiting = null;
        ResourceKey<Level> key = spot.level().dimension();
        if (!key.equals(this.dimension) || Math.max(Math.abs(spot.chunkX() - this.centerX), Math.abs(spot.chunkZ() - this.centerZ)) >= RECENTER_DISTANCE) {
            this.dimension = key;
            this.sink = VoxySink.create(spot.level());
            this.centerX = spot.chunkX();
            this.centerZ = spot.chunkZ();
            this.ring = this.fromRing;
            this.index = 0;
        }
        if (this.sink == null) {
            return null;
        }
        LongOpenHashSet dimensionDone = this.doneOf(spot.level());
        while (this.ring <= this.toRing) {
            // Square ring: 8 * ring chunks, starting at its north-west corner
            int side = 2 * this.ring;
            int i = this.index;
            int x;
            int z;
            if (i < side) {
                x = this.centerX - this.ring + i;
                z = this.centerZ - this.ring;
            } else if (i < 2 * side) {
                x = this.centerX + this.ring;
                z = this.centerZ - this.ring + i - side;
            } else if (i < 3 * side) {
                x = this.centerX + this.ring - (i - 2 * side);
                z = this.centerZ + this.ring;
            } else {
                x = this.centerX - this.ring;
                z = this.centerZ + this.ring - (i - 3 * side);
            }
            if (++this.index >= 4 * side) {
                this.index = 0;
                this.ring++;
            }
            long dx = x - this.centerX;
            long dz = z - this.centerZ;
            long distanceSquared = dx * dx + dz * dz;
            long chunk = LodGenerator.pack(x, z);
            if (distanceSquared <= (long) this.radius * this.radius || dimensionDone.contains(chunk) || this.generator.hostHas(spot.level(), chunk)) {
                continue;
            }
            // Finer close to the radius, where the rough LODs meet the real ones; Voxy shows coarser detail further away
            long radius = Math.max(1, this.radius);
            int step = distanceSquared <= 4 * radius * radius ? 4 : distanceSquared <= 16 * radius * radius ? 8 : 16;
            return new Job(spot.level(), x, z, step, dimensionDone, this.sink);
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
            LodGen.LOGGER.info("Horizon: {} LODs, {} per second, ring {}/{}, {} sections waiting for Voxy", total,
                    (total - this.builtAtLastStats) * 1000 / (now - this.lastStats), Math.min(this.ring, this.toRing), this.toRing, VoxySink.backlog());
        }
        this.lastStats = now;
        this.builtAtLastStats = total;
    }

    private synchronized LongOpenHashSet doneOf(ServerLevel level) {
        return this.done.computeIfAbsent(level.dimension(), key -> {
            Path root = level.getServer().getWorldPath(LevelResource.ROOT).resolve("lodgen").resolve("horizon");
            Path file = root.resolve(key.identifier().getNamespace()).resolve(key.identifier().getPath() + ".bin");
            this.files.put(key, file);
            return load(file);
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
        for (ResourceKey<Level> key : this.unsaved) {
            Path file = this.files.get(key);
            LongOpenHashSet set = this.done.get(key);
            if (file == null || set == null) {
                continue;
            }
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
        this.unsaved.clear();
    }

    private ChunkCache<Integer> heightsOf(ResourceKey<Level> dimension) {
        synchronized (this.heights) {
            return this.heights.computeIfAbsent(dimension, k -> new ChunkCache<>(HEIGHT_CACHE));
        }
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
