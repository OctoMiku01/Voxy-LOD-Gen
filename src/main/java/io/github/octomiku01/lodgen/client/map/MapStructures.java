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
    }

    /** One marker as the map shows it */
    record Shown(StructureMarkers.Marker marker, ItemStack icon, Component name) {
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
            markers.put(key(marker), new Shown(marker, new ItemStack(iconOf(marker.id())), nameOf(marker.id())));
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

    /** Translated for vanilla's, else made from the id: minecraft:village_plains becomes "Village Plains" */
    private static Component nameOf(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder fallback = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).split("_")) {
            if (!word.isEmpty()) {
                if (!fallback.isEmpty()) {
                    fallback.append(' ');
                }
                fallback.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return Component.translatableWithFallback("lodgen.structure." + id.replace(':', '.').replace('/', '.'), fallback.toString());
    }
}
