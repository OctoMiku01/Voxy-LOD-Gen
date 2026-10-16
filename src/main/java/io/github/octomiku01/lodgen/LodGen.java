package io.github.octomiku01.lodgen;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LodGen implements ModInitializer {
    public static final String MOD_ID = "lodgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static @Nullable LodGenerator generator;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(LodChunkPayload.TYPE, LodChunkPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Benchmark mode (-Dlodgen.benchmark=true) measures generation speed without Voxy and without a player
            boolean benchmark = Boolean.getBoolean("lodgen.benchmark");
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
            if (Boolean.getBoolean("lodgen.mptest")) {
                MpTest.tick(server, generator);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("lodgen")
                        .executes(context -> reply(context, generator == null ? "not running" : generator.status(context.getSource().getPlayer())))
                        .then(Commands.literal("pause").executes(context -> setPaused(context, true)))
                        .then(Commands.literal("resume").executes(context -> setPaused(context, false)))
                        .then(Commands.literal("reset").executes(LodGen::reset))
                        .then(Commands.literal("compare").executes(LodGen::compare))
        ));
    }

    /** Applies changed settings: the generator starts over with them, keeping every player's progress */
    public static void restart(MinecraftServer server) {
        server.execute(() -> {
            boolean benchmark = Boolean.getBoolean("lodgen.benchmark");
            if (generator != null) {
                generator.stop();
                generator = null;
            }
            LodConfig config = LodConfig.load();
            if (config.enabled) {
                generator = new LodGenerator(server, config, benchmark);
            }
        });
    }

    private static int setPaused(CommandContext<CommandSourceStack> context, boolean paused) {
        if (generator == null) {
            return reply(context, "not running");
        }
        generator.setPaused(paused);
        return reply(context, paused ? "paused" : "resumed");
    }

    // e.g. after deleting Voxy's data: all LODs of the current dimension are sent again
    private static int reset(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (generator == null || player == null) {
            return reply(context, "not running");
        }
        return reply(context, generator.reset(player) ? "your LODs in this dimension will be generated again" : "you get no LODs");
    }

    // Diagnostics: how well the LODs around the player match the real chunks there
    private static int compare(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return reply(context, "only players can compare");
        }
        LodCompare.start(context.getSource(), player);
        return 1;
    }

    private static int reply(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal("[Voxy LOD Gen] " + message), false);
        return 1;
    }
}
