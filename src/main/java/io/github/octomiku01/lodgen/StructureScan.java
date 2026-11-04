package io.github.octomiku01.lodgen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Finds the structures around the players for the 3D map where no LOD was generated with this version: worlds whose
 * LODs were done before, and explored land. Only decides which structures start in each chunk, like generation does
 * first, without any terrain; most chunks are ruled out by the structure placement rules at once. Goes through regions
 * of 32 x 32 chunks, nearest first, on one thread of the lowest priority, and remembers which regions it did (see
 * {@link StructureMarkers}), so each is done once per world.
 */
final class StructureScan {
    static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final long IDLE_MILLIS = 2_000;

    private final LodGenerator generator;
    private final LodConfig config;
    private final Thread thread;
    private final Map<ResourceKey<Level>, LodStructures> structures = new HashMap<>(); // scan thread only
    private volatile boolean running = true;
    /** Regions done since the scan last had nothing to do, and since when; scan thread only */
    private int regionsDone;
    private long busySince;

    StructureScan(LodGenerator generator, LodConfig config) {
        this.generator = generator;
        this.config = config;
        this.thread = new LodWorkerThread(this::run, "LodGen-Structures");
        this.thread.setPriority(Thread.MIN_PRIORITY);
        this.thread.start();
    }

    void stop() {
        this.running = false;
        this.thread.interrupt();
        try {
            this.thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (this.running) {
            try {
                if (this.generator.isPaused() || this.generator.isThrottled()) {
                    Thread.sleep(IDLE_MILLIS);
                } else if (this.scanNext()) {
                    if (this.regionsDone++ == 0) {
                        this.busySince = System.currentTimeMillis();
                    }
                } else {
                    if (this.regionsDone > 0) {
                        LodGen.LOGGER.info("Found the structures of {} regions for the 3D map in {} s", this.regionsDone,
                                (System.currentTimeMillis() - this.busySince) / 1000);
                        this.regionsDone = 0;
                    }
                    Thread.sleep(IDLE_MILLIS);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LodGen.LOGGER.error("Finding structures for the 3D map failed", e);
                try {
                    Thread.sleep(IDLE_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** @return whether a region was scanned; false when all around the players are done */
    private boolean scanNext() throws InterruptedException {
        List<LodGenerator.Spot> spots = this.generator.playerSpots();
        LodGenerator.Spot bestSpot = null;
        long bestRegion = 0;
        long bestDistance = Long.MAX_VALUE;
        int regions = Math.floorDiv(this.config.radius, REGION_SIZE) + 1;
        for (LodGenerator.Spot spot : spots) {
            BorderBounds border = BorderBounds.of(spot.level(), this.config);
            int centerX = spot.chunkX() >> REGION_SHIFT;
            int centerZ = spot.chunkZ() >> REGION_SHIFT;
            for (int x = centerX - regions; x <= centerX + regions; x++) {
                for (int z = centerZ - regions; z <= centerZ + regions; z++) {
                    long distance = (long) (x - centerX) * (x - centerX) + (long) (z - centerZ) * (z - centerZ);
                    if (distance >= bestDistance || !overlaps(border, x, z)) {
                        continue;
                    }
                    long region = LodGenerator.pack(x, z);
                    if (!StructureMarkers.isScanned(spot.level(), region)) {
                        bestSpot = spot;
                        bestRegion = region;
                        bestDistance = distance;
                    }
                }
            }
        }
        if (bestSpot == null) {
            return false;
        }
        ServerLevel level = bestSpot.level();
        LodStructures finder = this.structures.computeIfAbsent(level.dimension(), key -> new LodStructures(level));
        int minX = ChunkPos.getX(bestRegion) << REGION_SHIFT;
        int minZ = ChunkPos.getZ(bestRegion) << REGION_SHIFT;
        for (int x = minX; x < minX + REGION_SIZE; x++) {
            for (int z = minZ; z < minZ + REGION_SIZE; z++) {
                if (!this.running) {
                    return false;
                }
                finder.findStarts(x, z);
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        StructureMarkers.markScanned(level, bestRegion);
        return true;
    }

    private static boolean overlaps(BorderBounds border, int regionX, int regionZ) {
        int minX = regionX << REGION_SHIFT;
        int minZ = regionZ << REGION_SHIFT;
        return minX + REGION_SIZE - 1 >= border.minX() && minX <= border.maxX() && minZ + REGION_SIZE - 1 >= border.minZ() && minZ <= border.maxZ();
    }
}
