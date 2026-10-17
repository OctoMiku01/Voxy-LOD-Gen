package io.github.octomiku01.lodgen.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.octomiku01.lodgen.LodGen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
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
