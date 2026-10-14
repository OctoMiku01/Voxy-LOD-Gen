package io.github.octomiku01.lodgen;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LodGen implements ModInitializer {
    public static final String MOD_ID = "lodgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static @Nullable LodGenerator generator;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Benchmark mode (-Dlodgen.benchmark=true) measures generation speed without Voxy and without a player
            boolean benchmark = Boolean.getBoolean("lodgen.benchmark");
            // Voxy renders on the client: the LODs are handed to it directly, which only works in single player
            if (server.isDedicatedServer() && !benchmark) {
                LOGGER.info("Dedicated server: LOD generation is only available in single player for now");
                return;
            }
            if (!benchmark && !FabricLoader.getInstance().isModLoaded("voxy")) {
                LOGGER.warn("Voxy is not installed, no LODs are generated");
                return;
            }
            LodConfig config = LodConfig.load();
            if (config.enabled) {
                generator = new LodGenerator(server, config, benchmark);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (generator != null) {
                generator.stop();
                generator = null;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (generator != null) {
                generator.onServerTick();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("lodgen")
                        .executes(context -> reply(context, generator == null ? "not running" : generator.status()))
                        .then(Commands.literal("pause").executes(context -> setPaused(context, true)))
                        .then(Commands.literal("resume").executes(context -> setPaused(context, false)))
        ));
    }

    private static int setPaused(CommandContext<CommandSourceStack> context, boolean paused) {
        if (generator == null) {
            return reply(context, "not running");
        }
        generator.setPaused(paused);
        return reply(context, paused ? "paused" : "resumed");
    }

    private static int reply(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal("[LOD Gen] " + message), false);
        return 1;
    }
}
