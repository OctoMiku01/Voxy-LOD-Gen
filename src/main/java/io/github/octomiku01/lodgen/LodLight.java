package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Light for generated chunks, spread like vanilla's light engine: sky light falls straight down through transparent
 * blocks and spreads sideways under trees and overhangs and into caves, block light spreads from lava, glowstone and
 * other light sources. Each step costs one level, or the block's light dampening if that is higher.
 * <p>
 * Vanilla's engine itself is not used: light mods such as ScalableLux replace it with one that only works inside the
 * chunk system. Shapes of partial blocks (slabs, stairs) are ignored, they only let a little more light through.
 */
final class LodLight {
    private static final int MAX_LEVEL = 15;
    /** How far around overhangs (floating islands, cliffs) and into caves the ground counts as visible, in blocks */
    private static final int VISIBLE_REACH = 63;
    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Large arrays, reused by each worker thread for its next build */
    private static final ThreadLocal<byte[][]> BUFFERS = ThreadLocal.withInitial(() -> new byte[][]{new byte[0], new byte[0], new byte[0]});

    private final int minX;
    private final int minZ;
    private final int width;
    private final int height;
    private final int minSectionY;
    private final boolean hasSky;
    /** Low 4 bits: sky light; high 4 bits: light dampening of the block */
    private final byte[] sky;
    private final byte[] block;
    /** Cells that can be seen from the sky: connected to it through air and water, see {@link #findVisible} */
    private final byte[] visible;

    private LodLight(int minX, int minZ, int size, int height, int minSectionY, boolean hasSky) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = size * 16;
        this.height = height;
        this.minSectionY = minSectionY;
        this.hasSky = hasSky;
        int cells = this.width * this.width * height;
        byte[][] buffers = BUFFERS.get();
        if (buffers[0].length < cells) {
            buffers[0] = new byte[cells];
            buffers[1] = new byte[cells];
            buffers[2] = new byte[cells];
        }
        this.sky = buffers[0];
        this.block = buffers[1];
        this.visible = buffers[2];
        Arrays.fill(this.sky, 0, cells, (byte) 0);
        Arrays.fill(this.block, 0, cells, (byte) 0);
        Arrays.fill(this.visible, 0, cells, (byte) 0);
    }

    /**
     * Lights the {@code size} x {@code size} chunks starting at the given chunk. Light reaches at most 15 blocks, so
     * only the border of the area misses light from outside. The result stays valid until this thread lights again.
     */
    static LodLight light(ServerLevel level, Long2ObjectOpenHashMap<ProtoChunk> chunks, int minX, int minZ, int size) {
        ProtoChunk any = chunks.get(LodGenerator.pack(minX, minZ));
        // Above the highest section with blocks there is only sky
        int sections = 1;
        for (int x = minX; x < minX + size; x++) {
            for (int z = minZ; z < minZ + size; z++) {
                LevelChunkSection[] chunkSections = chunks.get(LodGenerator.pack(x, z)).getSections();
                for (int i = chunkSections.length - 1; i >= sections; i--) {
                    if (!chunkSections[i].hasOnlyAir()) {
                        sections = i + 1;
                        break;
                    }
                }
            }
        }
        LodLight light = new LodLight(minX, minZ, size, sections * 16, any.getMinSectionY(), level.dimensionType().hasSkyLight());
        IntArrayList[] queue = newQueue();
        light.readBlocks(chunks, size, sections, queue);
        light.spread(light.block, false, queue);
        if (light.hasSky) {
            light.addSkySources(queue);
            light.spread(light.sky, true, queue);
            light.findVisible(level.getSeaLevel() - light.minSectionY * 16);
        }
        return light;
    }

    private static IntArrayList[] newQueue() {
        IntArrayList[] queue = new IntArrayList[MAX_LEVEL + 1];
        for (int level = 0; level <= MAX_LEVEL; level++) {
            queue[level] = new IntArrayList();
        }
        return queue;
    }

    private int index(int x, int y, int z) {
        return (y * this.width + z) * this.width + x;
    }

    // Stores each block's light dampening and starts block light at every light source
    private void readBlocks(Long2ObjectOpenHashMap<ProtoChunk> chunks, int size, int sections, IntArrayList[] queue) {
        for (int chunkX = 0; chunkX < size; chunkX++) {
            for (int chunkZ = 0; chunkZ < size; chunkZ++) {
                LevelChunkSection[] chunkSections = chunks.get(LodGenerator.pack(this.minX + chunkX, this.minZ + chunkZ)).getSections();
                for (int sectionIndex = 0; sectionIndex < sections; sectionIndex++) {
                    LevelChunkSection section = chunkSections[sectionIndex];
                    if (section.hasOnlyAir()) {
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (state.isAir()) {
                                    continue;
                                }
                                int index = this.index(chunkX * 16 + x, sectionIndex * 16 + y, chunkZ * 16 + z);
                                this.sky[index] = (byte) (Math.min(MAX_LEVEL, state.getLightDampening()) << 4);
                                int emission = state.getLightEmission();
                                if (emission > 0) {
                                    this.block[index] = (byte) emission;
                                    queue[emission].add(index);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private int dampening(int index) {
        return (this.sky[index] >> 4) & 15;
    }

    // Full sky light falls down each column until the first block that dampens it
    private void addSkySources(IntArrayList[] queue) {
        int[] bottom = new int[this.width * this.width];
        for (int z = 0; z < this.width; z++) {
            for (int x = 0; x < this.width; x++) {
                int y = this.height - 1;
                for (; y >= 0; y--) {
                    int index = this.index(x, y, z);
                    if (this.dampening(index) != 0) {
                        break;
                    }
                    this.sky[index] |= MAX_LEVEL;
                }
                bottom[z * this.width + x] = y + 1;
            }
        }
        // Only the edges of the lit columns spread further: their bottom, and the sides facing a deeper neighbour
        for (int z = 0; z < this.width; z++) {
            for (int x = 0; x < this.width; x++) {
                int from = bottom[z * this.width + x];
                int to = from + 1;
                if (x > 0) to = Math.max(to, bottom[z * this.width + x - 1]);
                if (x < this.width - 1) to = Math.max(to, bottom[z * this.width + x + 1]);
                if (z > 0) to = Math.max(to, bottom[(z - 1) * this.width + x]);
                if (z < this.width - 1) to = Math.max(to, bottom[(z + 1) * this.width + x]);
                for (int y = from; y < Math.min(to, this.height); y++) {
                    queue[MAX_LEVEL].add(this.index(x, y, z));
                }
            }
        }
    }

    private int get(byte[] light, boolean packed, int index) {
        return packed ? light[index] & 15 : light[index];
    }

    private void set(byte[] light, boolean packed, int index, int level) {
        light[index] = (byte) (packed ? (light[index] & 0xF0) | level : level);
    }

    // Spreads from the brightest cells down, so every cell is final once its level is processed
    private void spread(byte[] light, boolean packed, IntArrayList[] queue) {
        for (int level = MAX_LEVEL; level > 1; level--) {
            IntArrayList cells = queue[level];
            for (int i = 0; i < cells.size(); i++) {
                int index = cells.getInt(i);
                if (this.get(light, packed, index) != level) {
                    continue;
                }
                int x = index % this.width;
                int z = (index / this.width) % this.width;
                int y = index / (this.width * this.width);
                for (int[] direction : DIRECTIONS) {
                    int nx = x + direction[0];
                    int ny = y + direction[1];
                    int nz = z + direction[2];
                    if (nx < 0 || nz < 0 || ny < 0 || nx >= this.width || nz >= this.width || ny >= this.height) {
                        continue;
                    }
                    int neighbour = this.index(nx, ny, nz);
                    int next = level - Math.max(1, this.dampening(neighbour));
                    if (next > this.get(light, packed, neighbour)) {
                        this.set(light, packed, neighbour, next);
                        queue[next].add(neighbour);
                    }
                }
            }
            cells.clear();
        }
        queue[1].clear();
    }

    /**
     * Marks what can be seen from the sky: the open columns down to the first opaque block (through water, however
     * deep), and from there sideways and down through air and water for {@link #VISIBLE_REACH} blocks, under
     * floating islands, overhangs and into cave openings. Deeper inside caves stays hidden.
     * <p>
     * The area ends somewhere, and what lies beyond is unknown: air at its sides above sea level counts as open too,
     * so a chunk built on its own below a floating island larger than itself still keeps the ground under it.
     */
    private void findVisible(int seaLevel) {
        IntArrayList queue = new IntArrayList();
        int[] bottom = new int[this.width * this.width];
        for (int z = 0; z < this.width; z++) {
            for (int x = 0; x < this.width; x++) {
                int y = this.height - 1;
                for (; y >= 0; y--) {
                    int index = this.index(x, y, z);
                    if (this.dampening(index) >= MAX_LEVEL) {
                        break;
                    }
                    this.visible[index] = VISIBLE_REACH;
                }
                bottom[z * this.width + x] = y + 1;
                if (x == 0 || z == 0 || x == this.width - 1 || z == this.width - 1) {
                    for (int side = Math.max(0, seaLevel); side <= y; side++) {
                        int index = this.index(x, side, z);
                        if (this.dampening(index) < MAX_LEVEL) {
                            this.visible[index] = VISIBLE_REACH;
                            queue.add(index);
                        }
                    }
                }
            }
        }
        // Like the sky light sources: only the edges of the open columns need to spread
        for (int z = 0; z < this.width; z++) {
            for (int x = 0; x < this.width; x++) {
                int from = bottom[z * this.width + x];
                int to = from + 1;
                if (x > 0) to = Math.max(to, bottom[z * this.width + x - 1]);
                if (x < this.width - 1) to = Math.max(to, bottom[z * this.width + x + 1]);
                if (z > 0) to = Math.max(to, bottom[(z - 1) * this.width + x]);
                if (z < this.width - 1) to = Math.max(to, bottom[(z + 1) * this.width + x]);
                for (int y = from; y < Math.min(to, this.height); y++) {
                    queue.add(this.index(x, y, z));
                }
            }
        }
        // Breadth first: every step costs one, so the first visit is the closest
        for (int i = 0; i < queue.size(); i++) {
            int index = queue.getInt(i);
            int reach = this.visible[index];
            if (reach <= 1) {
                continue;
            }
            int x = index % this.width;
            int z = (index / this.width) % this.width;
            int y = index / (this.width * this.width);
            for (int[] direction : DIRECTIONS) {
                int nx = x + direction[0];
                int ny = y + direction[1];
                int nz = z + direction[2];
                if (nx < 0 || nz < 0 || ny < 0 || nx >= this.width || nz >= this.width || ny >= this.height) {
                    continue;
                }
                int neighbour = this.index(nx, ny, nz);
                if (this.visible[neighbour] == 0 && this.dampening(neighbour) < MAX_LEVEL) {
                    this.visible[neighbour] = (byte) (reach - 1);
                    queue.add(neighbour);
                }
            }
        }
    }

    /**
     * The lowest section of a chunk with anything that can be seen from the sky ({@link #findVisible}). Without sky
     * (the Nether): the bottom of the world. Outside the lit area nothing is known: {@link Integer#MAX_VALUE}.
     */
    int lowestVisibleSection(int chunkX, int chunkZ) {
        int size = this.width / 16;
        if (chunkX < this.minX || chunkZ < this.minZ || chunkX >= this.minX + size || chunkZ >= this.minZ + size) {
            return Integer.MAX_VALUE;
        }
        if (!this.hasSky) {
            return this.minSectionY;
        }
        int baseX = (chunkX - this.minX) * 16;
        int baseZ = (chunkZ - this.minZ) * 16;
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (this.visible[this.index(baseX + x, y, baseZ + z)] != 0) {
                        return this.minSectionY + (y >> 4);
                    }
                }
            }
        }
        return this.minSectionY + this.height / 16;
    }

    /** Sky light of a section; null if no sky reaches it */
    @Nullable DataLayer skyLight(ChunkPos pos, int sectionY) {
        if (!this.hasSky) {
            return null;
        }
        return this.layer(this.sky, true, pos, sectionY);
    }

    /** Block light of a section; null if it is dark */
    @Nullable DataLayer blockLight(ChunkPos pos, int sectionY) {
        return this.layer(this.block, false, pos, sectionY);
    }

    private @Nullable DataLayer layer(byte[] light, boolean packed, ChunkPos pos, int sectionY) {
        int baseY = (sectionY - this.minSectionY) * 16;
        if (baseY >= this.height) {
            return packed ? LodChunk.FULL_SKY_LIGHT : null;
        }
        int baseX = (pos.x() - this.minX) * 16;
        int baseZ = (pos.z() - this.minZ) * 16;
        DataLayer layer = new DataLayer();
        boolean dark = true;
        boolean full = true;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int level = this.get(light, packed, this.index(baseX + x, baseY + y, baseZ + z));
                    dark &= level == 0;
                    full &= level == MAX_LEVEL;
                    if (level != 0) {
                        layer.set(x, y, z, level);
                    }
                }
            }
        }
        if (dark) {
            return null;
        }
        return full && packed ? LodChunk.FULL_SKY_LIGHT : layer;
    }
}
