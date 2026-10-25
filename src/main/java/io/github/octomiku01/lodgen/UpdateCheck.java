package io.github.octomiku01.lodgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.annotations.Nullable;

/**
 * Asks GitHub once per start whether a newer release of this mod exists for the same Minecraft version, so the player
 * can be told. Updating itself is left to the player or to Mod Synchronizer.
 */
public final class UpdateCheck {
    /** As many as GitHub gives at once: a bugfix for an older Minecraft version may come after many newer releases */
    private static final String RELEASES ="https://api.github.com/repos/OctoMiku01/Voxy-LOD-Gen/releases?per_page=100";
    /** Downloads of the automatic updates older versions had, which would otherwise stay in the mods folder */
    private static final String UPDATE_SUFFIX = ".update";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** A newer release */
    public record Update(String version, String page) {
    }

    private static volatile @Nullable Update update;

    private UpdateCheck() {
    }

    /** Seconds after joining a world before the message about the update shows */
    public static int messageDelaySeconds() {
        return LodConfig.load().updateMessageDelay;
    }

    /** The newer release found, or null while there is none (or the check has not finished) */
    public static @Nullable Update update() {
        return update;
    }

    public static void start() {
        if (!LodConfig.load().checkForUpdates) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                check();
            } catch (IOException | RuntimeException e) {
                LodGen.LOGGER.warn("Could not check for a newer version of Voxy LOD Gen: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "LOD Gen update check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check() throws IOException, InterruptedException {
        ModContainer mod = FabricLoader.getInstance().getModContainer(LodGen.MOD_ID).orElseThrow();
        // The version is "<mod version>+<Minecraft version>", the release tags "v<mod version>-<Minecraft version>"
        String[] installed = mod.getMetadata().getVersion().getFriendlyString().split("\\+", 2);
        if (installed.length < 2) {
            return;
        }
        String version = installed[0];
        String minecraft = installed[1];
        @Nullable Path jar = runningJar(mod);
        if (jar != null) {
            deleteOldDownloads(jar);
        }

        JsonArray releases;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<String> response = client.send(request(RELEASES).header("Accept", "application/vnd.github+json").build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("GitHub answered " + response.statusCode());
            }
            releases = JsonParser.parseString(response.body()).getAsJsonArray();

            @Nullable JsonObject newest = null;
            String newestVersion = version;
            String suffix = "-" + minecraft;
            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();
                String tag = release.get("tag_name").getAsString();
                if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()
                        || !tag.startsWith("v") || !tag.endsWith(suffix)) {
                    continue;
                }
                String candidate = tag.substring(1, tag.length() - suffix.length());
                if (compare(candidate, newestVersion) > 0) {
                    newest = release;
                    newestVersion = candidate;
                }
            }
            if (newest == null) {
                LodGen.LOGGER.info("Voxy LOD Gen {} is the newest version for Minecraft {}", version, minecraft);
                return;
            }
            String page = newest.get("html_url").getAsString();
            LodGen.LOGGER.info("Voxy LOD Gen {} is available (installed: {}): {}", newestVersion, version, page);
            update = new Update(newestVersion, page);
        }
    }

    private static HttpRequest.Builder request(String url) {
        String version = FabricLoader.getInstance().getModContainer(LodGen.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "Voxy-LOD-Gen/" + version);
    }

    /** Older versions downloaded updates as voxy-lodgen-*.jar.update next to the jar; they are not used any more */
    private static void deleteOldDownloads(Path jar) {
        try (Stream<Path> files = Files.list(jar.getParent())) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("voxy-lodgen-") && (name.endsWith(".jar" + UPDATE_SUFFIX) || name.endsWith(".jar" + UPDATE_SUFFIX + ".part"))) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            LodGen.LOGGER.warn("Could not look for old update downloads", e);
        }
    }

    /** The jar this mod runs from, if it is a plain jar file (not in a development environment) */
    private static @Nullable Path runningJar(ModContainer mod) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return null;
        }
        for (Path path : mod.getOrigin().getPaths()) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                return path.toAbsolutePath();
            }
        }
        return null;
    }

    /** Compares versions like "0.4.3" part by part, as numbers */
    static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int difference = Integer.compare(part(left, i), part(right, i));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
