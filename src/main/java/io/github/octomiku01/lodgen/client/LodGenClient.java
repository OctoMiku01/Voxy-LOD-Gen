package io.github.octomiku01.lodgen.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.octomiku01.lodgen.FarEntitiesPayload;
import io.github.octomiku01.lodgen.LodChunkPayload;
import io.github.octomiku01.lodgen.MapPlayersPayload;
import io.github.octomiku01.lodgen.client.map.MapPlayers;
import io.github.octomiku01.lodgen.client.map.MapScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class LodGenClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Without a receiver the server sees that this client cannot use LODs and sends none
        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            ClientPlayNetworking.registerGlobalReceiver(LodChunkPayload.TYPE, (payload, context) -> ClientLodReceiver.receive(payload, context.client()));
            ClientTickEvents.END_CLIENT_TICK.register(ClientLodReceiver::tick);
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                ClientLodReceiver.clear();
                FarEntities.reset();
                MapPlayers.clear();
            });
            ClientPlayNetworking.registerGlobalReceiver(FarEntitiesPayload.TYPE, (payload, context) -> FarEntities.receive(payload, context.client()));
            ClientTickEvents.END_CLIENT_TICK.register(FarEntities::tick);
            LevelRenderEvents.COLLECT_SUBMITS.register(FarEntities::render);

            // The 3D map is what Voxy renders, seen from high above
            ClientPlayNetworking.registerGlobalReceiver(MapPlayersPayload.TYPE, (payload, context) -> MapPlayers.receive(payload));
            KeyMapping openMap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lodgen.map", InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_M, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lodgen", "main"))));
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                while (openMap.consumeClick()) {
                    if (client.gui.screen() == null && client.level != null) {
                        client.gui.setScreen(new MapScreen(openMap));
                    }
                }
            });
        }
    }
}
