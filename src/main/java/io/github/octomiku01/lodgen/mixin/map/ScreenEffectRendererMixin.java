package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No fire, lava, water or block overlay over the 3D map: they belong to where the player is, not the camera */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void lodgen$noEffectsOnMap(CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }
}
