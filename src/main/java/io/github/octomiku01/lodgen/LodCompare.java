package io.github.octomiku01.lodgen;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Diagnostics ({@code /lodgen compare}): builds the LODs of the 16 x 16 chunks around a player and compares them
 * with the real chunks there that exist on disk, column by column. Tells apart differences in features (top block),
 * in the terrain itself (ground height) and in biomes, and lists the most common block differences.
 */
final class LodCompare {
    private static final int SIZE = 16;
    private static final int SHOWN_DIFFERENCES = 8;

    /** The highest block of a column, the ground below plants and trees, and the biome there */
    private record Column(int top, String topBlock, int ground, String biome) {
    }

    private LodCompare() {
    }

    static void start(CommandSourceStack source, ServerPlayer player) {
        MinecraftServer server = source.getServer();
        ServerLevel level = (ServerLevel) player.level();
        LodChunkBuilder builder = LodChunkBuilder.create(level, true);
        if (builder == null) {
            reply(source, "this dimension is not supported");
            return;
        }
        ChunkPos center = player.chunkPosition();
        int minX = center.x() - SIZE / 2;
        int minZ = center.z() - SIZE / 2;
        SavedChunks saved = new SavedChunks(DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region"));
        reply(source, "building the LODs around you, this takes a few seconds...");

        Thread thread = new Thread(() -> {
            try {
                List<ChunkPos> real = new ArrayList<>();
                for (int x = minX; x < minX + SIZE; x++) {
                    for (int z = minZ; z < minZ + SIZE; z++) {
                        if (saved.exists(x, z)) {
                            real.add(new ChunkPos(x, z));
                        }
                    }
                }
                List<LodChunk> withFeatures = builder.buildTile(minX, minZ, SIZE, () -> false);
                List<LodChunk> reversed = builder.buildTile(minX, minZ, SIZE, () -> false, true);
                Map<ChunkPos, LodChunk> terrainOnly = new HashMap<>();
                for (ChunkPos pos : real) {
                    terrainOnly.put(pos, builder.build(pos));
                }
                server.execute(() -> compare(source, level, real, withFeatures, reversed, terrainOnly, minX, minZ));
            } catch (Throwable t) {
                LodGen.LOGGER.error("LOD comparison failed", t);
                server.execute(() -> reply(source, "failed: " + t));
            }
        }, "LodGen-Compare");
        thread.setDaemon(true);
        thread.start();
    }

    private static void compare(CommandSourceStack source, ServerLevel level, List<ChunkPos> real,
                                List<LodChunk> withFeatures, List<LodChunk> reversed, Map<ChunkPos, LodChunk> terrainOnly,
                                int minX, int minZ) {
        if (real.isEmpty()) {
            reply(source, "no real chunks around you to compare with");
            return;
        }
        int columns = 0;
        int sameTop = 0;
        int sameTopTerrain = 0;
        int sameTopReversed = 0;
        int sameGround = 0;
        int sameBiome = 0;
        Map<String, Integer> differences = new HashMap<>();
        Map<String, Integer> biomeDifferences = new HashMap<>();
        for (ChunkPos pos : real) {
            LevelChunk chunk = level.getChunk(pos.x(), pos.z());
            Int2ObjectOpenHashMap<LevelChunkSection> realSections = new Int2ObjectOpenHashMap<>();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                realSections.put(chunk.getMinSectionY() + i, sections[i]);
            }
            int index = (pos.x() - minX) * SIZE + (pos.z() - minZ);
            Int2ObjectOpenHashMap<LevelChunkSection> lod = sectionsOf(withFeatures.get(index));
            Int2ObjectOpenHashMap<LevelChunkSection> other = sectionsOf(reversed.get(index));
            Int2ObjectOpenHashMap<LevelChunkSection> terrain = sectionsOf(terrainOnly.get(pos));
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Column r = column(realSections, x, z);
                    Column l = column(lod, x, z);
                    Column t = column(terrain, x, z);
                    Column o = column(other, x, z);
                    columns++;
                    if (r.top == l.top) {
                        sameTop++;
                    } else {
                        differences.merge(r.topBlock + " -> " + l.topBlock, 1, Integer::sum);
                    }
                    if (r.top == t.top) {
                        sameTopTerrain++;
                    }
                    if (l.top == o.top) {
                        sameTopReversed++;
                    }
                    if (r.ground == l.ground) {
                        sameGround++;
                    }
                    if (r.biome.equals(l.biome)) {
                        sameBiome++;
                    } else {
                        biomeDifferences.merge(r.biome + " -> " + l.biome, 1, Integer::sum);
                    }
                }
            }
        }
        reply(source, String.format("compared %d real chunks (%d columns): top block %d%% identical (terrain only: %d%%), ground %d%%, biome %d%%",
                real.size(), columns, 100 * sameTop / columns, 100 * sameTopTerrain / columns, 100 * sameGround / columns, 100 * sameBiome / columns));
        reply(source, String.format("LOD against the same LOD decorated in reverse chunk order: top block %d%% identical"
                + " (the rest depends only on which overlapping tree grows first)", 100 * sameTopReversed / columns));
        report(source, "top block real -> LOD", differences);
        report(source, "biome real -> LOD", biomeDifferences);
    }

    private static void report(CommandSourceStack source, String title, Map<String, Integer> differences) {
        if (differences.isEmpty()) {
            return;
        }
        reply(source, "most common differences, " + title + ":");
        differences.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(SHOWN_DIFFERENCES)
                .forEach(entry -> reply(source, "  " + entry.getValue() + "x " + entry.getKey()));
    }

    private static Int2ObjectOpenHashMap<LevelChunkSection> sectionsOf(LodChunk chunk) {
        Int2ObjectOpenHashMap<LevelChunkSection> sections = new Int2ObjectOpenHashMap<>();
        chunk.forEachSection((sectionY, section, skyLight, blockLight) -> sections.put(sectionY, section));
        return sections;
    }

    private static Column column(Int2ObjectOpenHashMap<LevelChunkSection> sections, int x, int z) {
        int top = Integer.MIN_VALUE;
        String topBlock = "air";
        int ground = Integer.MIN_VALUE;
        int maxSection = sections.keySet().intStream().max().orElse(0);
        int minSection = sections.keySet().intStream().min().orElse(0);
        for (int sectionY = maxSection; sectionY >= minSection && ground == Integer.MIN_VALUE; sectionY--) {
            LevelChunkSection section = sections.get(sectionY);
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            for (int y = 15; y >= 0; y--) {
                BlockState state = section.getBlockState(x, y, z);
                if (state.isAir()) {
                    continue;
                }
                if (top == Integer.MIN_VALUE) {
                    top = sectionY * 16 + y;
                    topBlock = state.getBlock().getDescriptionId().replace("block.", "");
                }
                if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                    ground = sectionY * 16 + y;
                    break;
                }
            }
        }
        String biome = "?";
        if (top != Integer.MIN_VALUE) {
            int y = Math.max(ground, minSection * 16);
            LevelChunkSection section = sections.get(y >> 4);
            if (section != null) {
                Holder<Biome> holder = section.getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2);
                biome = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("?");
            }
        }
        return new Column(top, topBlock, ground, biome);
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[Voxy LOD Gen] " + message), false);
        LodGen.LOGGER.info("compare: {}", message);
    }
}
