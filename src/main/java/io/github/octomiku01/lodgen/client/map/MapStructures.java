package io.github.octomiku01.lodgen.client.map;

import io.github.octomiku01.lodgen.MapStructuresPayload;
import io.github.octomiku01.lodgen.StructureMarkers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** The structures the server told about, for the 3D map, per dimension; render thread only */
public final class MapStructures {
    /** Vanilla structures by the start of their name, each with an item that stands for it */
    private static final Map<String, Item> ICONS = new LinkedHashMap<>();

    static {
        ICONS.put("village", Items.BELL);
        ICONS.put("pillager_outpost", Items.CROSSBOW);
        ICONS.put("mansion", Items.TOTEM_OF_UNDYING);
        ICONS.put("monument", Items.PRISMARINE_SHARD);
        ICONS.put("desert_pyramid", Items.CHISELED_SANDSTONE);
        ICONS.put("jungle_pyramid", Items.MOSSY_COBBLESTONE);
        ICONS.put("igloo", Items.SNOW_BLOCK);
        ICONS.put("swamp_hut", Items.CAULDRON);
        ICONS.put("shipwreck", Items.OAK_BOAT);
        ICONS.put("ocean_ruin", Items.SUSPICIOUS_SAND);
        ICONS.put("ruined_portal", Items.CRYING_OBSIDIAN);
        ICONS.put("stronghold", Items.ENDER_EYE);
        ICONS.put("fortress", Items.NETHER_BRICKS);
        ICONS.put("bastion", Items.GILDED_BLACKSTONE);
        ICONS.put("end_city", Items.PURPUR_BLOCK);
        ICONS.put("ancient_city", Items.SCULK_SHRIEKER);
        ICONS.put("trail_ruins", Items.BRUSH);
        ICONS.put("trial_chambers", Items.TRIAL_KEY);
        ICONS.put("abandoned_camp", Items.CAMPFIRE);
    }

    /** Common and small: shown smaller, so they do not crowd out the others (the ocean is full of ruins and wrecks) */
    private static final List<String> MINOR = List.of("ocean_ruin", "shipwreck", "ruined_portal", "abandoned_camp");
    /** Last parts of structure ids that do not tell what it is */
    private static final java.util.Set<String> GENERIC_NAMES = java.util.Set.of("default", "main", "base", "start", "normal", "common", "variant");
    /** Vanilla structures with one variant per biome that all share one name */
    private static final List<String> SHARED_NAMES = List.of("abandoned_camp");

    /** One marker as the map shows it */
    record Shown(StructureMarkers.Marker marker, ItemStack icon, Component name, boolean minor) {
    }

    private static final Map<ResourceKey<Level>, Map<Long, Shown>> BY_DIMENSION = new HashMap<>();

    private MapStructures() {
    }

    public static void receive(MapStructuresPayload payload) {
        Map<Long, Shown> markers = BY_DIMENSION.computeIfAbsent(payload.dimension(), key -> new HashMap<>());
        if (payload.reset()) {
            markers.clear();
        }
        for (StructureMarkers.Marker marker : payload.markers()) {
            markers.put(key(marker), new Shown(marker, new ItemStack(iconOf(marker.id())), nameOf(marker.id()), isMinor(marker.id())));
        }
    }

    public static void clear() {
        BY_DIMENSION.clear();
    }

    static Collection<Shown> current(Minecraft minecraft) {
        if (minecraft.level == null) {
            return List.of();
        }
        Map<Long, Shown> markers = BY_DIMENSION.get(minecraft.level.dimension());
        return markers == null ? List.of() : new ArrayList<>(markers.values());
    }

    private static long key(StructureMarkers.Marker marker) {
        return ((long) marker.x() << 32) ^ (marker.z() & 0xFFFFFFFFL);
    }

    /** "monster_room/lower" becomes "Monster Room Lower" */
    private static String words(String text) {
        StringBuilder result = new StringBuilder();
        for (String word : text.split("[_/.-]")) {
            if (!word.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append(' ');
                }
                result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return result.toString();
    }

    private static boolean isMinor(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return id.startsWith("minecraft:") && MINOR.stream().anyMatch(path::startsWith);
    }

    /** Vanilla's by name; those of mods too where their name says what they are, else a compass */
    private static Item iconOf(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        for (Map.Entry<String, Item> entry : ICONS.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, Item> entry : ICONS.entrySet()) {
            if (path.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return Items.COMPASS;
    }

    /**
     * Translated for vanilla's, else made from the id, with the mod in front: the last part of its path, which mods
     * use for the name (folders before it are categories); with the part before when the last says nothing.
     * trek:overworld/common/mushroom_house becomes "Trek: Mushroom House", trek:monster_room/lower/default
     * "Trek: Lower Default"
     */
    private static Component nameOf(String id) {
        int colon = id.indexOf(':');
        String path = id.substring(colon + 1);
        String[] parts = path.split("/");
        String last = parts[parts.length - 1];
        String shown = parts.length > 1 && (GENERIC_NAMES.contains(last) || last.matches("[0-9_]+")) ? parts[parts.length - 2] + "_" + last : last;
        StringBuilder fallback = new StringBuilder();
        if (colon > 0 && !id.startsWith("minecraft:")) {
            fallback.append(words(id.substring(0, colon))).append(": ");
        }
        fallback.append(words(shown));
        String key = id.replace(':', '.').replace('/', '.');
        if (id.startsWith("minecraft:")) {
            for (String shared : SHARED_NAMES) {
                if (path.startsWith(shared)) {
                    key = "minecraft." + shared;
                }
            }
        }
        return Component.translatableWithFallback("lodgen.structure." + key, fallback.toString());
    }
}
