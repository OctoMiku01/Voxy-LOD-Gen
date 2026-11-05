package io.github.octomiku01.lodgen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /lodgen bench <radius>}: measures how fast this machine builds LODs with features, with the threads of the
 * performance setting. Builds the tiles of the square around where the command runs and throws them away; the normal
 * generation pauses meanwhile, so both do not share the CPU. Gives comparable numbers for bug reports and for choosing
 * a performance level. The result is also saved as a file, with what it ran on, see {@link LodBenchReport}.
 */
final class LodBench {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private LodBench() {
    }

    /** @param generator paused while measuring, may be null when generation is turned off */
    static void start(CommandSourceStack source, int radius, LodConfig config, @Nullable LodGenerator generator) {
        MinecraftServer server = source.getServer();
        ServerLevel level = source.getLevel();
        LodChunkBuilder builder = LodChunkBuilder.create(level, config.structures, config.keepUnderground);
        if (!RUNNING.compareAndSet(false, true)) {
            reply(source, LodChat.warning("A benchmark is already running"));
            return;
        }
        ChunkPos center = ChunkPos.containing(BlockPos.containing(source.getPosition()));
        int size = config.tileSize;
        int minTileX = Math.floorDiv(center.x() - radius, size);
        int maxTileX = Math.floorDiv(center.x() + radius, size);
        int minTileZ = Math.floorDiv(center.z() - radius, size);
        int maxTileZ = Math.floorDiv(center.z() + radius, size);
        int tiles = (maxTileX - minTileX + 1) * (maxTileZ - minTileZ + 1);
        int threads = config.workerThreads();
        boolean wasPaused = generator == null || generator.isPaused();
        if (generator != null) {
            generator.setPaused(true);
        }
        reply(source, LodChat.info(String.format("Benchmark: %d tiles of %d x %d chunks with %d threads, the generation pauses meanwhile...",
                tiles, size, size, threads)));

        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new LodWorkerThread(task, "LodGen-Bench-" + threadNumber.incrementAndGet());
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        new LodWorkerThread(() -> {
            try {
                long start = System.nanoTime();
                List<Future<Integer>> results = new ArrayList<>(tiles);
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                        int x = tileX * size;
                        int z = tileZ * size;
                        results.add(workers.submit(() -> builder.buildTile(x, z, size, () -> false).size()));
                    }
                }
                long chunks = 0;
                for (Future<Integer> result : results) {
                    chunks += result.get();
                }
                double seconds = (System.nanoTime() - start) / 1e9;
                String result = String.format(Locale.ROOT, "%d chunks in %.1f s = %.1f chunks/s with %d threads (%s), %d cores",
                        chunks, seconds, chunks / seconds, threads, config.performance, LodPerformance.CORES);
                String line = "Benchmark done: " + result;
                LodGen.LOGGER.info(line);
                server.execute(() -> reply(source, LodChat.success(line)));
                // Generation is still paused: asking the system for the CPU does not disturb it
                LodBenchReport.Run run = new LodBenchReport.Run(result,
                        String.format(Locale.ROOT, "%d tiles of %d x %d chunks, %d chunks around chunk %d, %d", tiles, size, size, radius, center.x(), center.z()),
                        level.dimension().identifier().toString(), builder.generatorName(), server.isDedicatedServer());
                try {
                    Path file = LodBenchReport.write(run, config);
                    Path shown = FabricLoader.getInstance().getGameDir().relativize(file);
                    LodGen.LOGGER.info("Benchmark saved to {}", file);
                    server.execute(() -> reply(source, saved(shown, file)));
                } catch (IOException | RuntimeException e) {
                    LodGen.LOGGER.error("Could not save the benchmark", e);
                    server.execute(() -> reply(source, LodChat.warning("Could not save the benchmark to a file: " + e)));
                }
            } catch (Throwable t) {
                LodGen.LOGGER.error("Benchmark failed", t);
                server.execute(() -> reply(source, LodChat.error("Benchmark failed: " + t)));
            } finally {
                workers.shutdownNow();
                if (generator != null && !wasPaused) {
                    server.execute(() -> generator.setPaused(false));
                }
                RUNNING.set(false);
            }
        }, "LodGen-Bench").start();
    }

    /** Where the file is; clicking copies its full path, to find it or attach it to a bug report */
    private static Component saved(Path shown, Path file) {
        String path = file.toAbsolutePath().toString();
        return LodChat.tag().append(LodChat.label("Saved with versions, hardware, settings and mods to "))
                .append(Component.literal(shown.toString().replace('\\', '/')).withStyle(style -> style.withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(path))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy the full path\n" + path)))));
    }

    private static void reply(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }
}
