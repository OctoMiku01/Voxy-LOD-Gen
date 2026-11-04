package io.github.octomiku01.lodgen.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.octomiku01.lodgen.LodGen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The 3D map's own settings, changed on the map itself; kept in {@code config/lodgen-map.json} */
public final class MapSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lodgen-map.json");
    private static MapSettings current;

    /** The single player game pauses while the map is open */
    boolean pauseSingleplayer = true;
    double cameraSpeed = 1.0;
    /**
     * Draw the real chunks near the player from Voxy's LODs too: the game only draws them near its camera, which on
     * the map is far away from them, and would leave a hole
     */
    boolean onlyLods = true;
    TimeOfDay timeOfDay = TimeOfDay.DAY;
    /** Whether to show where the structures are, as far as the server says */
    StructureMarkers structureMarkers = StructureMarkers.AUTO;
    /** Show the own waypoints in the game too, with their name and distance */
    boolean waypointsInGame = true;
    /** Show how far the LODs are, see MapProgress */
    boolean progressView = false;

    /** When the map shows structures */
    public enum StructureMarkers {
        /** In creative and spectator mode; in survival finding them is part of the game */
        AUTO,
        ON,
        OFF;

        boolean shown(Minecraft minecraft) {
            return switch (this) {
                case ON -> true;
                case OFF -> false;
                case AUTO -> minecraft.gameMode != null && !minecraft.gameMode.getPlayerMode().isSurvival();
            };
        }

        Component label(Minecraft minecraft) {
            Component name = Component.translatable("lodgen.map.structures." + this.name().toLowerCase(java.util.Locale.ROOT));
            if (this != AUTO) {
                return name;
            }
            // Auto says what it does in the current game mode
            return Component.translatable("lodgen.map.structures.autoNow", name,
                    Component.translatable(this.shown(minecraft) ? "options.on" : "options.off"));
        }
    }

    /** The time of day the map shows */
    public enum TimeOfDay {
        REAL(0L, false, 0),
        DAY(6000L, false, 0),
        SUNSET(12000L, false, 3),
        NIGHT(18000L, true, 11),
        DAWN(23000L, false, 2);

        public final long ticks;
        public final boolean darkOutside;
        public final int skyDarken;

        TimeOfDay(long ticks, boolean darkOutside, int skyDarken) {
            this.ticks = ticks;
            this.darkOutside = darkOutside;
            this.skyDarken = skyDarken;
        }

        Component label() {
            return Component.translatable("lodgen.map.time." + this.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static MapSettings get() {
        if (current == null) {
            current = load();
        }
        return current;
    }

    private static MapSettings load() {
        MapSettings settings = new MapSettings();
        try {
            if (Files.exists(FILE)) {
                MapSettings read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), MapSettings.class);
                if (read != null) {
                    settings = read;
                }
            }
        } catch (IOException | RuntimeException e) {
            LodGen.LOGGER.error("Could not read {}, using the defaults", FILE, e);
        }
        settings.cameraSpeed = Math.clamp(settings.cameraSpeed, 0.25, 4.0);
        if (settings.timeOfDay == null) {
            settings.timeOfDay = TimeOfDay.DAY;
        }
        if (settings.structureMarkers == null) {
            settings.structureMarkers = StructureMarkers.AUTO;
        }
        return settings;
    }

    void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not write {}", FILE, e);
        }
    }

    public boolean onlyLods() {
        return this.onlyLods;
    }

    void toggleOnlyLods() {
        this.onlyLods = !this.onlyLods;
        this.save();
    }

    public TimeOfDay timeOfDay() {
        return this.timeOfDay;
    }

    boolean structuresShown(Minecraft minecraft) {
        return this.structureMarkers.shown(minecraft);
    }

    public boolean waypointsInGame() {
        return this.waypointsInGame;
    }

    boolean progressView() {
        return this.progressView;
    }

    void toggleProgressView() {
        this.progressView = !this.progressView;
        this.save();
    }

    void toggleWaypointsInGame() {
        this.waypointsInGame = !this.waypointsInGame;
        this.save();
    }

    void cycleStructures() {
        StructureMarkers[] values = StructureMarkers.values();
        this.structureMarkers = values[(this.structureMarkers.ordinal() + 1) % values.length];
        this.save();
    }

    void togglePause() {
        this.pauseSingleplayer = !this.pauseSingleplayer;
        this.save();
    }

    void changeCameraSpeed(double delta) {
        this.cameraSpeed = Math.clamp(this.cameraSpeed + delta, 0.25, 4.0);
        this.save();
    }

    void cycleTimeOfDay() {
        TimeOfDay[] values = TimeOfDay.values();
        this.timeOfDay = values[(this.timeOfDay.ordinal() + 1) % values.length];
        this.save();
    }
}
