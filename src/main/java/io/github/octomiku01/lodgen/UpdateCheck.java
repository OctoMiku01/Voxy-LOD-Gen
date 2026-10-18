package io.github.octomiku01.lodgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.annotations.Nullable;

/**
 * Asks GitHub once per start whether a newer release of this mod exists for the same Minecraft version. With
 * {@code autoUpdate} on, its jar is downloaded next to the running one as {@code .jar.update}, which Fabric does not
 * load, and swapped in when the game quits: the running jar cannot be replaced while it is in use, and Windows does
 * not even let it be deleted then, so there a small script finishes the swap after the game has exited. If that
 * fails, the old jar stays and the swap is tried again at the next quit.
 */
public final class UpdateCheck {
    private static final String RELEASES = "https://api.github.com/repos/OctoMiku01/Voxy-LOD-Gen/releases?per_page=30";
    private static final String UPDATE_SUFFIX = ".update";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** A newer release; {@code downloaded} once its jar waits to be swapped in when the game quits */
    public record Update(String version, String page, boolean downloaded) {
    }

    private static volatile @Nullable Update update;
    private static boolean installScheduled;

    private UpdateCheck() {
    }

    /** The newer release found, or null while there is none (or the check has not finished) */
    public static @Nullable Update update() {
        return update;
    }

    public static void start() {
        LodConfig config = LodConfig.load();
        if (!config.checkForUpdates && !config.autoUpdate) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                check(config.autoUpdate);
            } catch (IOException | RuntimeException e) {
                LodGen.LOGGER.warn("Could not check for a newer version of Voxy LOD Gen: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "LOD Gen update check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check(boolean autoUpdate) throws IOException, InterruptedException {
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
            adoptDownloaded(jar, version);
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
            Update current = update;
            if (current != null && current.downloaded() && current.version().equals(newestVersion)) {
                return;
            }
            update = new Update(newestVersion, page, false);
            LodGen.LOGGER.info("Voxy LOD Gen {} is available (installed: {}): {}", newestVersion, version, page);
            if (!autoUpdate) {
                return;
            }
            if (jar == null) {
                LodGen.LOGGER.info("Not updating automatically: the mod is not running from a jar in the mods folder");
                return;
            }
            @Nullable JsonObject asset = modJar(newest, newestVersion + "+" + minecraft);
            if (asset == null) {
                LodGen.LOGGER.warn("Release {} has no jar of the mod to update to", newest.get("tag_name").getAsString());
                return;
            }
            Path target = jar.resolveSibling(asset.get("name").getAsString() + UPDATE_SUFFIX);
            try {
                download(client, asset, target);
            } catch (IOException e) {
                LodGen.LOGGER.warn("Could not download Voxy LOD Gen {}: {}", newestVersion, e.getMessage());
                return;
            }
            update = new Update(newestVersion, page, true);
            scheduleInstall(jar, target);
            LodGen.LOGGER.info("Downloaded Voxy LOD Gen {}, it replaces {} when the game quits", newestVersion, jar.getFileName());
        }
    }

    private static HttpRequest.Builder request(String url) {
        String version = FabricLoader.getInstance().getModContainer(LodGen.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "Voxy-LOD-Gen/" + version);
    }

    /** The mod's jar among the release's files, not the sources */
    private static @Nullable JsonObject modJar(JsonObject release, String fullVersion) {
        String name = "voxy-lodgen-" + fullVersion + ".jar";
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            if (asset.get("name").getAsString().equals(name)) {
                return asset;
            }
        }
        return null;
    }

    private static void download(HttpClient client, JsonObject asset, Path target) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        HttpResponse<InputStream> response = client.send(request(asset.get("browser_download_url").getAsString()).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = new DigestInputStream(response.body(), sha256)) {
            if (response.statusCode() != 200) {
                throw new IOException("GitHub answered " + response.statusCode() + " for the download");
            }
            Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            // GitHub states each file's SHA-256: only a download that matches it is swapped in, one without it never
            JsonElement digest = asset.get("digest");
            if (digest == null || digest.isJsonNull() || !digest.getAsString().startsWith("sha256:")) {
                throw new IOException("GitHub states no SHA-256 checksum for " + asset.get("name").getAsString());
            }
            String expected = digest.getAsString().toLowerCase(Locale.ROOT);
            String actual = "sha256:" + HexFormat.of().formatHex(sha256.digest());
            if (!expected.equals(actual)) {
                throw new IOException("the download does not match its checksum (expected " + expected + ", got " + actual + ")");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** An update downloaded at an earlier start that could not be swapped in yet is swapped in at this quit */
    private static void adoptDownloaded(Path jar, String version) {
        try (Stream<Path> files = Files.list(jar.getParent())) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("voxy-lodgen-") || !name.endsWith(".jar" + UPDATE_SUFFIX)) {
                    continue;
                }
                String fileVersion = name.substring("voxy-lodgen-".length()).split("\\+", 2)[0];
                if (compare(fileVersion, version) > 0 && update == null) {
                    update = new Update(fileVersion, "https://github.com/OctoMiku01/Voxy-LOD-Gen/releases", true);
                    scheduleInstall(jar, file);
                } else {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            LodGen.LOGGER.warn("Could not look for a downloaded update", e);
        }
    }

    private static synchronized void scheduleInstall(Path jar, Path downloaded) {
        if (installScheduled) {
            return;
        }
        installScheduled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> install(jar, downloaded), "LOD Gen update install"));
    }

    private static void install(Path jar, Path downloaded) {
        String name = downloaded.getFileName().toString();
        Path installed = downloaded.resolveSibling(name.substring(0, name.length() - UPDATE_SUFFIX.length()));
        try {
            Files.delete(jar);
        } catch (IOException e) {
            // Windows: the jar is still open, a script waits for the game to exit
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                installAfterExit(jar, downloaded, installed);
            }
            return;
        }
        try {
            Files.move(downloaded, installed, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not install the update {}", downloaded, e);
        }
    }

    private static void installAfterExit(Path jar, Path downloaded, Path installed) {
        try {
            Path script = Files.createTempFile("lodgen-update", ".cmd");
            Files.writeString(script, String.join("\r\n",
                    "@echo off",
                    "chcp 65001 >nul",
                    "for /l %%i in (1,1,60) do (",
                    "  del /f /q \"" + jar + "\" >nul 2>&1",
                    "  if not exist \"" + jar + "\" goto install",
                    "  ping -n 2 127.0.0.1 >nul",
                    ")",
                    "goto end",
                    ":install",
                    "move /y \"" + downloaded + "\" \"" + installed + "\" >nul",
                    ":end",
                    "(goto) 2>nul & del \"%~f0\"",
                    ""), StandardCharsets.UTF_8);
            new ProcessBuilder("cmd.exe", "/c", script.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            LodGen.LOGGER.error("Could not install the update {}", downloaded, e);
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
