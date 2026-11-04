package io.github.octomiku01.lodgen;

import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.server.level.ServerLevel;

/**
 * The chunks a dimension's world border lets players reach: every chunk at least partly inside it. LODs beyond it
 * would only show land nobody can go to, and cost generation time and bandwidth for nothing.
 */
record BorderBounds(int minX, int minZ, int maxX, int maxZ) {
    static final BorderBounds EVERYWHERE = new BorderBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    /** Where the border is now; while it moves, where it is at this moment */
    static BorderBounds of(ServerLevel level, LodConfig config) {
        if (!config.worldBorder) {
            return EVERYWHERE;
        }
        WorldBorder border = level.getWorldBorder();
        return new BorderBounds(
                Math.floorDiv((int) Math.floor(border.getMinX()), 16),
                Math.floorDiv((int) Math.floor(border.getMinZ()), 16),
                // A chunk that only touches the border from outside is not reachable
                Math.floorDiv((int) Math.ceil(border.getMaxX()) - 1, 16),
                Math.floorDiv((int) Math.ceil(border.getMaxZ()) - 1, 16));
    }

    boolean contains(int chunkX, int chunkZ) {
        return chunkX >= this.minX && chunkX <= this.maxX && chunkZ >= this.minZ && chunkZ <= this.maxZ;
    }

    /** The furthest square ring around the chunk that still has a chunk inside; rings beyond it have none */
    long reach(int chunkX, int chunkZ) {
        return Math.max(Math.max((long) chunkX - this.minX, (long) this.maxX - chunkX),
                Math.max((long) chunkZ - this.minZ, (long) this.maxZ - chunkZ));
    }

    /** Whether nothing of other lies beyond these bounds, so a search over them has not missed anything of it */
    boolean covers(BorderBounds other) {
        return other.minX >= this.minX && other.maxX <= this.maxX && other.minZ >= this.minZ && other.maxZ <= this.maxZ;
    }
}
