package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No F3 axes in the middle of the 3D map */
@Mixin(DebugCrosshairRenderer.class)
public abstract class DebugCrosshairRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lodgen$noAxesOnMap(CameraRenderState camera, int size, CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }
}
