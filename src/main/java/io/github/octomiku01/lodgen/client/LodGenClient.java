package io.github.octomiku01.lodgen.client;

import io.github.octomiku01.lodgen.LodChunkPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

public class LodGenClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Without a receiver the server sees that this client cannot use LODs and sends none
        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            ClientPlayNetworking.registerGlobalReceiver(LodChunkPayload.TYPE, (payload, context) -> ClientLodReceiver.receive(payload, context.client()));
            ClientTickEvents.END_CLIENT_TICK.register(ClientLodReceiver::tick);
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientLodReceiver.clear());
        }
    }
}
