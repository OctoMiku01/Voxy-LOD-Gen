package io.github.octomiku01.lodgen.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.octomiku01.lodgen.LodGen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * The player's own waypoints, set on the 3D map: per world (a single player save, or a server by its address), kept
 * on this computer in {@code config/lodgen-waypoints/}. Need nothing on the server. Render thread only.
 */
public final class Waypoints {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("lodgen-waypoints");
    /** Given to new waypoints in turn: easy to tell apart on the map */
    private static final int[] COLORS = {0xFFE84855, 0xFFF9DC5C, 0xFF7BE070, 0xFF3BA4FF, 0xFFC879FF, 0xFFFF9F43};

    /** @param dimension e.g. minecraft:overworld; color with alpha, as drawn */
    public record Waypoint(String name, int x, int y, int z, String dimension, int color) {
        Waypoint renamed(String newName) {
            return new Waypoint(newName, this.x, this.y, this.z, this.dimension, this.color);
        }
    }

    private static @Nullable String loadedWorld;
    private static List<Waypoint> all = new ArrayList<>();

    private Waypoints() {
    }

    /** Those of the world and dimension the player is in */
    public static List<Waypoint> current(Minecraft minecraft) {
        String world = worldId(minecraft);
        if (world == null || minecraft.level == null) {
            return List.of();
        }
        load(world);
        String dimension = minecraft.level.dimension().identifier().toString();
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint waypoint : all) {
            if (waypoint.dimension.equals(dimension)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    static void add(Minecraft minecraft, String name, int x, int y, int z) {
        String world = worldId(minecraft);
        if (world == null || minecraft.level == null) {
            return;
        }
        load(world);
        all.add(new Waypoint(name, x, y, z, minecraft.level.dimension().identifier().toString(), COLORS[all.size() % COLORS.length]));
        save();
    }

    static void rename(Minecraft minecraft, Waypoint waypoint, String name) {
        replace(minecraft, waypoint, waypoint.renamed(name));
    }

    static void remove(Minecraft minecraft, Waypoint waypoint) {
        replace(minecraft, waypoint, null);
    }

    private static void replace(Minecraft minecraft, Waypoint waypoint, @Nullable Waypoint with) {
        String world = worldId(minecraft);
        if (world == null) {
            return;
        }
        load(world);
        int index = all.indexOf(waypoint);
        if (index >= 0) {
            if (with == null) {
                all.remove(index);
            } else {
                all.set(index, with);
            }
            save();
        }
    }

    /** The save folder's name in single player, the server's address otherwise; null outside a world */
    private static @Nullable String worldId(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null) {
            Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "singleplayer-" + root.getFileName();
        }
        ServerData server = minecraft.getCurrentServer();
        return server == null ? null : "server-" + server.ip;
    }

    private static Path file(String world) {
        // Only what any file system takes
        return FOLDER.resolve(world.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".json");
    }

    private static void load(String world) {
        if (world.equals(loadedWorld)) {
            return;
        }
        loadedWorld = world;
        all = new ArrayList<>();
        Path file = file(world);
        try {
            if (Files.exists(file)) {
                List<Waypoint> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Waypoint>>() { }.getType());
                if (read != null) {
                    for (Waypoint waypoint : read) {
                        if (waypoint != null && waypoint.name != null && waypoint.dimension != null) {
                            all.add(waypoint);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LodGen.LOGGER.error("Could not read the waypoints in {}", file, e);
        }
    }

    private static void save() {
        if (loadedWorld == null) {
            return;
        }
        Path file = file(loadedWorld);
        try {
            Files.createDirectories(FOLDER);
            Files.writeString(file, GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not save the waypoints in {}", file, e);
        }
    }
}
