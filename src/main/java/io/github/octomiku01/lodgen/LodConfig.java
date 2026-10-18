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
    /** Worker threads while the game is paused (single player menu): it needs no CPU time then */
    int threadsWhilePaused = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    /** Also send the sections deep underground that no daylight reaches; they only show when looking through caves */
    boolean keepUnderground = false;
    /** Up to this distance (in chunks) LODs include features: trees, flowers, lakes... Further away only terrain */
    int featureRadius = 128;
    /** First terrain quickly up to the radius, then the details up to featureRadius, closest first; the terrain stays
     *  twice as far ahead. Off: each ring is finished with its details before the next one */
    boolean terrainFirst = true;
    /** Real chunks (explored, with the players' builds) become LODs too, read from the save, and are sent again
     *  when they change. Replaces what Voxy Server Side does for this */
    boolean sendSavedChunks = true;
    /** LODs with features also get structures: villages, towers, ruins... Costs some generation speed */
    boolean structures = true;
    /** Chunks with features are generated in tiles of this many chunks per side; larger tiles waste less work at
     *  their edges but take more memory */
    int tileSize = 16;
    /** Chunks closer than the view distance plus this margin are left to the game */
    int viewDistanceMargin = 0;
    /** Send LODs to other players whose client has this mod and Voxy */
    boolean sendToPlayers = true;
    /** Players beyond the view distance are shown to everyone who gets LODs, with what they ride */
    boolean farPlayers = true;
    /** How far from the player (in chunks) the 3D map may lead generation to where it looks; 0 turns that off */
    int mapRadius = 1024;
    /** Per player that gets LODs over the network; about 20 KiB each before the connection compresses them */
    int maxChunksPerSecondPerPlayer = 20;
    /** Ask GitHub at each start whether a newer version exists */
    boolean checkForUpdates = true;
    /** Download a newer version by itself and swap it in when the game quits */
    boolean autoUpdate = false;
    /** Seconds after joining a world before the update message shows, so it comes after the messages of joining */
    int updateMessageDelay = 10;

    static LodConfig load() {
        LodConfig config = new LodConfig();
        try {
            if (Files.exists(FILE)) {
                LodConfig read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), LodConfig.class);
                if (read != null) {
                    config = read;
                }
            }
        } catch (IOException | RuntimeException e) {
            LodGen.LOGGER.error("Could not read {}, using the defaults", FILE, e);
        }
        config.clamp();
        // Write it back so new options show up in the file
        config.save();
        return config;
    }

    void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not write {}", FILE, e);
        }
    }

    private void clamp() {
        this.radius = Math.max(1, this.radius);
        this.threads = Math.max(1, this.threads);
        this.threadsWhilePaused = Math.max(1, this.threadsWhilePaused);
        this.viewDistanceMargin = Math.max(0, this.viewDistanceMargin);
        this.featureRadius = Math.max(0, this.featureRadius);
        this.mapRadius = Math.max(0, this.mapRadius);
        this.tileSize = Math.clamp(this.tileSize, 1, 32);
        this.maxChunksPerSecondPerPlayer = Math.max(1, this.maxChunksPerSecondPerPlayer);
        this.updateMessageDelay = Math.clamp(this.updateMessageDelay, 0, 60);
    }
}
