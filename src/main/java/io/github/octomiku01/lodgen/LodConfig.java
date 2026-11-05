package io.github.octomiku01.lodgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
    /** How much of the CPU generation may use; CUSTOM uses the two thread counts below */
    LodPerformance performance = LodPerformance.BALANCED;
    /** Worker threads with the CUSTOM performance; the default leaves room for the game, rendering and Voxy itself */
    int threads = LodPerformance.BALANCED.threads();
    /** Worker threads with the CUSTOM performance while the game is paused (single player menu): it needs no CPU time then */
    int threadsWhilePaused = LodPerformance.BALANCED.threadsWhilePaused();
    /** Beyond the radius, up to this distance (in chunks), rough LODs show the horizon: only the height and the
     *  biome of the ground, far cheaper than real ones. Other players with this mod get it too (see
     *  maxHorizonChunksPerSecondPerPlayer); 0 turns it off */
    int horizonRadius = 0;
    /** While the server's ticks take longer than this (milliseconds, 50 is the budget of a tick), only one worker
     *  runs; 0 turns this off */
    int tickTimeLimit = 55;
    /** Also send the sections deep underground that no daylight reaches; they only show when looking through caves */
    boolean keepUnderground = false;
    /** No LODs for chunks beyond the world border, where nobody can go */
    boolean worldBorder = true;
    /** Players with the 3D map open get where the structures in the LODs are, shown as markers */
    boolean mapStructures = true;
    /** Up to this distance (in chunks) LODs include features: trees, flowers, lakes... Further away only terrain */
    int featureRadius = 256;
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
    /** radius for players without operator rights (permission level 2); the host and operators get radius itself.
     *  -1: the same for everyone, 0: no real LODs for them (only the horizon, if it is on) */
    int playerRadius = -1;
    /** mapRadius for players without operator rights; -1: the same for everyone, 0: their 3D map leads nowhere */
    int playerMapRadius = -1;
    /** Per player that gets LODs over the network; about 20 KiB each before the connection compresses them */
    int maxChunksPerSecondPerPlayer = 20;
    /** The horizon per player that gets it over the network; a few dozen bytes per chunk, their client builds it */
    int maxHorizonChunksPerSecondPerPlayer = 500;
    /** Ask GitHub at each start whether a newer version exists */
    boolean checkForUpdates = true;
    /** Seconds after joining a world before the update message shows, so it comes after the messages of joining */
    int updateMessageDelay = 10;

    static LodConfig load() {
        LodConfig config = new LodConfig();
        try {
            if (Files.exists(FILE)) {
                JsonElement json = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
                LodConfig read = GSON.fromJson(json, LodConfig.class);
                if (read != null) {
                    config = read;
                    // Files from before the performance levels: thread counts someone changed stay as CUSTOM
                    if (json.isJsonObject() && !json.getAsJsonObject().has("performance")
                            && (read.threads != LodPerformance.BALANCED.threads() || read.threadsWhilePaused != LodPerformance.BALANCED.threadsWhilePaused())) {
                        config.performance = LodPerformance.CUSTOM;
                    }
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

    /** The settings as they are written to the file */
    String toJson() {
        return GSON.toJson(this);
    }

    void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, this.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not write {}", FILE, e);
        }
    }

    /** The radius a player gets: operators all of it, others at most playerRadius */
    int radiusFor(boolean operator) {
        return operator || this.playerRadius < 0 ? this.radius : Math.min(this.playerRadius, this.radius);
    }

    /** The mapRadius a player gets: operators all of it, others at most playerMapRadius */
    int mapRadiusFor(boolean operator) {
        return operator || this.playerMapRadius < 0 ? this.mapRadius : Math.min(this.playerMapRadius, this.mapRadius);
    }

    /** Whether some players get less than others */
    boolean limitsPlayers() {
        return this.radiusFor(false) < this.radius || this.mapRadiusFor(false) < this.mapRadius;
    }

    /** Worker threads while playing */
    int workerThreads() {
        return this.performance == LodPerformance.CUSTOM ? this.threads : this.performance.threads();
    }

    /** Worker threads while the single player game is paused */
    int workerThreadsWhilePaused() {
        return this.performance == LodPerformance.CUSTOM ? Math.max(this.threads, this.threadsWhilePaused) : this.performance.threadsWhilePaused();
    }

    private void clamp() {
        if (this.performance == null) {
            this.performance = LodPerformance.BALANCED;
        }
        this.radius = Math.max(1, this.radius);
        this.tickTimeLimit = Math.max(0, this.tickTimeLimit);
        this.horizonRadius = Math.clamp(this.horizonRadius, 0, 2048);
        this.threads = Math.max(1, this.threads);
        this.threadsWhilePaused = Math.max(1, this.threadsWhilePaused);
        this.viewDistanceMargin = Math.max(0, this.viewDistanceMargin);
        this.featureRadius = Math.max(0, this.featureRadius);
        this.mapRadius = Math.max(0, this.mapRadius);
        this.playerRadius = Math.max(-1, this.playerRadius);
        this.playerMapRadius = Math.max(-1, this.playerMapRadius);
        this.tileSize = Math.clamp(this.tileSize, 1, 32);
        this.maxChunksPerSecondPerPlayer = Math.max(1, this.maxChunksPerSecondPerPlayer);
        this.maxHorizonChunksPerSecondPerPlayer = Math.max(1, this.maxHorizonChunksPerSecondPerPlayer);
        this.updateMessageDelay = Math.clamp(this.updateMessageDelay, 0, 60);
    }
}
