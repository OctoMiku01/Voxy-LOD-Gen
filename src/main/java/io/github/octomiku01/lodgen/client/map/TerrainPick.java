package io.github.octomiku01.lodgen.client.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Where a ray from the map's camera first hits the ground: in the chunks the game has loaded, else in Voxy's LODs.
 * Voxy has no interface for this, its sections are read directly; if a Voxy update changed them, nothing is hit.
 * <p>
 * Render thread only.
 */
final class TerrainPick {
    private TerrainPick() {
    }

    /**
     * The block above the first one the ray hits, or null when it hits nothing. Above {@code topY} nothing counts:
     * in the Nether that is where the map cuts it open.
     */
    static @Nullable BlockPos pick(ClientLevel level, double ox, double oy, double oz, double dx, double dy, double dz, int topY) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dy >= 0 || length == 0) {
            return null;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        double start = oy > topY ? (topY - oy) / dy : 0;
        double end = (level.getMinY() - oy) / dy;

        WorldEngine engine;
        try {
            engine = WorldIdentifier.ofEngineNullable(level);
        } catch (LinkageError e) {
            engine = null;
        }
        Long2ObjectOpenHashMap<WorldSection> sections = new Long2ObjectOpenHashMap<>();
        try {
            // Where Voxy has not stored the finest LODs, a coarser one is the best there is
            for (int lvl = 0; lvl <= (engine == null ? 0 : WorldEngine.MAX_LOD_LAYER); lvl++) {
                BlockPos hit = march(level, engine, sections, lvl, ox, oy, oz, dx, dy, dz, start, end);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        } catch (RuntimeException | LinkageError e) {
            io.github.octomiku01.lodgen.LodGen.LOGGER.warn("Could not find the ground under the mouse on the 3D map", e);
            return null;
        } finally {
            for (WorldSection section : sections.values()) {
                if (section != null) {
                    section.release();
                }
            }
        }
    }

    /** Steps through the cells of one LOD level along the ray, as in Amanatides and Woo's voxel traversal */
    private static @Nullable BlockPos march(ClientLevel level, @Nullable WorldEngine engine, Long2ObjectOpenHashMap<WorldSection> sections,
                                            int lvl, double ox, double oy, double oz, double dx, double dy, double dz, double start, double end) {
        int scale = 1 << lvl;
        double px = ox + dx * start;
        double py = oy + dy * start;
        double pz = oz + dz * start;
        int x = Math.floorDiv((int) Math.floor(px), scale);
        int y = Math.floorDiv((int) Math.floor(py), scale);
        int z = Math.floorDiv((int) Math.floor(pz), scale);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = -1;
        int stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : scale / Math.abs(dx);
        double deltaY = scale / Math.abs(dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : scale / Math.abs(dz);
        double nextX = dx == 0 ? Double.POSITIVE_INFINITY : start + ((x + (dx > 0 ? 1 : 0)) * (double) scale - px) / dx;
        double nextY = start + (y * (double) scale - py) / dy;
        double nextZ = dz == 0 ? Double.POSITIVE_INFINITY : start + ((z + (dz > 0 ? 1 : 0)) * (double) scale - pz) / dz;
        double t = start;
        while (t <= end) {
            if (solid(level, engine, sections, lvl, x, y, z)) {
                return new BlockPos(x * scale + scale / 2, (y + 1) * scale, z * scale + scale / 2);
            }
            if (nextX < nextY && nextX < nextZ) {
                t = nextX;
                nextX += deltaX;
                x += stepX;
            } else if (nextY < nextZ) {
                t = nextY;
                nextY += deltaY;
                y += stepY;
            } else {
                t = nextZ;
                nextZ += deltaZ;
                z += stepZ;
            }
        }
        return null;
    }

    private static boolean solid(ClientLevel level, @Nullable WorldEngine engine, Long2ObjectOpenHashMap<WorldSection> sections,
                                 int lvl, int x, int y, int z) {
        // The loaded chunks are what is really there now, with the latest builds
        if (lvl == 0 && level.hasChunk(x >> 4, z >> 4)) {
            return !level.getBlockState(new BlockPos(x, y, z)).isAir();
        }
        if (engine == null) {
            return false;
        }
        // Null for a section Voxy has no blocks in: the ray passes through it
        long key = WorldEngine.getWorldSectionId(lvl, x >> 5, y >> 5, z >> 5);
        WorldSection section;
        if (sections.containsKey(key)) {
            section = sections.get(key);
        } else {
            section = engine.acquireIfExists(lvl, x >> 5, y >> 5, z >> 5);
            sections.put(key, section);
        }
        return section != null && !Mapper.isAir(section._unsafeGetRawDataArray()[WorldSection.getIndex(x & 31, y & 31, z & 31)]);
    }
}
