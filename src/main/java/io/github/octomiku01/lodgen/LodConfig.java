package io.github.octomiku01.lodgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

final class LodConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lodgen.json");

    boolean enabled = true;
    /** How far from the player LODs are generated, in chunks */
    int radius = 256;
    /** Worker threads; the default leaves room for the game, rendering and Voxy itself */
    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    /** Up to this distance (in chunks) LODs include features: trees, flowers, lakes... Further away only terrain */
    int featureRadius = 64;
    /** LODs with features also get structures: villages, towers, ruins... Costs some generation speed */
    boolean structures = true;
    /** Chunks with features are generated in tiles of this many chunks per side; larger tiles waste less work at
     *  their edges but take more memory */
    int tileSize = 16;
    /** Chunks closer than the view distance plus this margin are left to the game */
    int viewDistanceMargin = 2;
    /** Send LODs to other players whose client has this mod and Voxy */
    boolean sendToPlayers = true;
    /** Per player that gets LODs over the network; about 20 KiB each before the connection compresses them */
    int maxChunksPerSecondPerPlayer = 20;

    static LodConfig load() {
        LodConfig config = new LodConfig();
        try {
            if (Files.exists(FILE)) {
                LodConfig read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), LodConfig.class);
                if (read != null) {
                    config = read;
                }
            }
            // Write it back so new options show up in the file
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LodGen.LOGGER.error("Could not read {}, using the defaults", FILE, e);
        }
        config.radius = Math.max(1, config.radius);
        config.threads = Math.max(1, config.threads);
        config.viewDistanceMargin = Math.max(0, config.viewDistanceMargin);
        config.featureRadius = Math.max(0, config.featureRadius);
        config.tileSize = Math.clamp(config.tileSize, 1, 32);
        config.maxChunksPerSecondPerPlayer = Math.max(1, config.maxChunksPerSecondPerPlayer);
        return config;
    }
}
