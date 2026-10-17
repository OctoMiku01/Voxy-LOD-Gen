package io.github.octomiku01.lodgen.mixin.voxy;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.MapSettings;
import me.cortex.voxy.client.VoxyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The 3D map is drawn from Voxy's LODs alone, also where the game has real chunks: Voxy leaves those to the game,
 * which only draws them near its camera, far away from them on the map, and would leave a hole around the player.
 * Voxy has this switch built in (for debugging), always off; it already holds the loaded chunks as LODs. A setting
 * on the map.
 */
@Mixin(value = VoxyClient.class, remap = false)
public abstract class VoxyClientMixin {
    @Inject(method = "disableSodiumChunkRender", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lodgen$onlyLodsOnMap(CallbackInfoReturnable<Boolean> info) {
        if (MapCamera.isActive() && MapSettings.get().onlyLods()) {
            info.setReturnValue(true);
        }
    }
}
