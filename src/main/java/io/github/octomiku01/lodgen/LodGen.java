package io.github.octomiku01.lodgen;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
    private static @Nullable FarPlayers farPlayers;

    @Override
    public void onInitialize() {
        UpdateCheck.start();
        PayloadTypeRegistry.clientboundPlay().register(LodChunkPayload.TYPE, LodChunkPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FarEntitiesPayload.TYPE, FarEntitiesPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPlayersPayload.TYPE, MapPlayersPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapFocusPayload.TYPE, MapFocusPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapFocusPayload.TYPE, (payload, context) -> {
            if (generator != null) {
                generator.setMapFocus(context.player(), payload);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Benchmark mode (-Dlodgen.benchmark=true) measures generation speed without Voxy and without a player
            boolean benchmark = Boolean.getBoolean("lodgen.benchmark");
            LodConfig config = LodConfig.load();
            if (config.enabled) {
                generator = new LodGenerator(server, config, benchmark);
            }
            startFarPlayers(server, config);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (generator != null) {
                generator.stop();
                generator = null;
            }
            farPlayers = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (generator != null) {
                generator.onServerTick();
                if (server.getTickCount() % 20 == 0) {
                    generator.sendMapPlayers();
                }
            }
            if (farPlayers != null) {
                farPlayers.tick();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("lodgen")
                        .executes(context -> reply(context, generator == null ? notRunning() : generator.statusMessage(context.getSource().getPlayer())))
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
            if (farPlayers != null) {
                farPlayers.stop();
            }
            startFarPlayers(server, config);
        });
    }

    private static void startFarPlayers(MinecraftServer server, LodConfig config) {
        farPlayers = config.farPlayers && config.sendToPlayers ? new FarPlayers(server, config.radius) : null;
    }

    private static int setPaused(CommandContext<CommandSourceStack> context, boolean paused) {
        if (generator == null) {
            return reply(context, notRunning());
        }
        generator.setPaused(paused);
        return reply(context, paused ? LodChat.warning("Paused, /lodgen resume continues") : LodChat.success("Resumed"));
    }

    // e.g. after deleting Voxy's data: all LODs of the current dimension are sent again
    private static int reset(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (generator == null) {
            return reply(context, notRunning());
        }
        if (player == null) {
            return reply(context, LodChat.error("Only players can reset their LODs"));
        }
        return reply(context, generator.reset(player) ? LodChat.success("Your LODs in this dimension will be generated again")
                : LodChat.warning("You get no LODs: this mod or Voxy is missing on your client"));
    }

    // Diagnostics: how well the LODs around the player match the real chunks there
    private static int compare(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return reply(context, LodChat.error("Only players can compare"));
        }
        LodCompare.start(context.getSource(), player);
        return 1;
    }

    private static Component notRunning() {
        return LodChat.error("Not running: LOD generation is turned off in the settings");
    }

    private static int reply(CommandContext<CommandSourceStack> context, Component message) {
        context.getSource().sendSuccess(() -> message, false);
        return 1;
    }
}
