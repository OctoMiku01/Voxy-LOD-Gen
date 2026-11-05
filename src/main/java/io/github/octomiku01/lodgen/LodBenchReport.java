package io.github.octomiku01.lodgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.jetbrains.annotations.Nullable;

/**
 * The result of {@code /lodgen bench} as a text file in {@code lodgen/bench/}, with what it ran on: the versions, the
 * hardware, the settings and the mods. Made to be attached to bug reports as it is, so it reads as Markdown.
 */
final class LodBenchReport {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    /** How long asking the system for the CPU's name may take */
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    /** The graphics card, set by the client; null on a dedicated server */
    private static volatile @Nullable Supplier<String> gpu;

    private LodBenchReport() {
    }

    static void setGpu(Supplier<String> supplier) {
        gpu = supplier;
    }

    /** What was measured, in the words of the chat */
    record Run(String result, String area, String dimension, String generator, boolean dedicatedServer) {
    }

    /** Writes the file; called off the server thread, since asking the system for the CPU can take a moment */
    static Path write(Run run, LodConfig config) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder text = new StringBuilder();
        text.append("# Voxy LOD Gen benchmark\n\n");
        line(text, "Date", TEXT_TIME.format(now));
        line(text, "Result", run.result);
        line(text, "Area", run.area);
        line(text, "Dimension", run.dimension);
        line(text, "Generator", run.generator);

        text.append("\n## Versions\n\n");
        line(text, "LOD Gen", version("lodgen"));
        line(text, "Minecraft", version("minecraft"));
        line(text, "Fabric Loader", version("fabricloader"));
        line(text, "Fabric API", version("fabric-api"));
        line(text, "Voxy", version("voxy"));
        line(text, "Sodium", version("sodium"));
        line(text, "Java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        line(text, "Side", run.dedicatedServer ? "dedicated server" : "single player / LAN host");

        text.append("\n## Hardware\n\n");
        line(text, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        line(text, "CPU", cpuName() + ", " + LodPerformance.CORES + " threads");
        line(text, "Memory", memory());
        Supplier<String> card = gpu;
        if (card != null && !run.dedicatedServer) {
            line(text, "GPU", describe(card));
        }

        text.append("\n## Settings (config/lodgen.json)\n\n```json\n").append(config.toJson()).append("\n```\n");

        List<ModContainer> mods = topLevelMods();
        text.append("\n## Mods (").append(mods.size()).append(")\n\n");
        for (ModContainer mod : mods) {
            ModMetadata meta = mod.getMetadata();
            text.append("- ").append(meta.getName()).append(" (").append(meta.getId()).append(") ").append(meta.getVersion().getFriendlyString()).append('\n');
        }

        Path folder = FabricLoader.getInstance().getGameDir().resolve("lodgen").resolve("bench");
        Files.createDirectories(folder);
        Path file = folder.resolve("bench-" + FILE_TIME.format(now) + ".md");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static void line(StringBuilder text, String label, String value) {
        text.append("- **").append(label).append(":** ").append(value).append('\n');
    }

    private static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
    }

    /** Mods in the mods folder (and the game itself), not the libraries they bundle */
    private static List<ModContainer> topLevelMods() {
        List<ModContainer> mods = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.getContainingMod().isEmpty() && !"builtin".equals(mod.getMetadata().getType())) {
                mods.add(mod);
            }
        }
        mods.sort(Comparator.comparing(mod -> mod.getMetadata().getName().toLowerCase(Locale.ROOT)));
        return mods;
    }

    private static String memory() {
        String text = String.format(Locale.ROOT, "%,d MiB for the game", Runtime.getRuntime().maxMemory() >> 20);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean system) {
            text += String.format(Locale.ROOT, ", %,d MiB in the machine", system.getTotalMemorySize() >> 20);
        }
        return text;
    }

    private static String describe(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return "unknown (" + e + ")";
        }
    }

    /** Java has no way to ask for it, so each system's own tool is asked */
    private static String cpuName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String name = null;
        try {
            if (os.contains("win")) {
                // "    ProcessorNameString    REG_SZ    AMD Ryzen 7 7800X3D 8-Core Processor"
                String line = run("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString");
                if (line != null && line.contains("REG_SZ")) {
                    name = line.substring(line.indexOf("REG_SZ") + "REG_SZ".length());
                }
            } else if (os.contains("mac")) {
                name = run("sysctl", "-n", "machdep.cpu.brand_string");
            } else {
                Path cpuinfo = Path.of("/proc/cpuinfo");
                if (Files.isReadable(cpuinfo)) {
                    for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
                        if (line.startsWith("model name")) {
                            name = line.substring(line.indexOf(':') + 1);
                            break;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LodGen.LOGGER.debug("Could not find the CPU's name", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (name == null || name.isBlank()) {
            String identifier = System.getenv("PROCESSOR_IDENTIFIER");
            return identifier != null ? identifier : "unknown";
        }
        return name.trim();
    }

    /** The command's last line that is not empty, or null */
    private static @Nullable String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String last = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    last = line;
                }
            }
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        return process.exitValue() == 0 ? last : null;
    }
}
