package io.github.octomiku01.lodgen.mixin.voxy;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.ShaderMap;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 3D map over a shader pack draws Voxy alone, after the world: inside the world it is held back. Takes none of
 * the method's parameters, so a change to them does not break it; if the method itself is gone, {@link ShaderMap}
 * notices.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin {
    // In the Nether the 3D map cuts the roof off with the near plane: Voxy makes its own, from the same place
    @ModifyArg(method = "setupViewport", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/VoxyRenderSystem;computeProjectionMat(Lme/cortex/voxy/client/core/RenderProperties;Lorg/joml/Matrix4fc;FF)Lorg/joml/Matrix4f;"),
            index = 2, require = 0)
    private float lodgen$mapNearPlane(float near) {
        MapCamera.voxyNearSeen = true;
        return MapCamera.near() > 0 ? MapCamera.near() : near;
    }

    @ModifyArg(method = "setupViewport", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/Viewport;setProjection(Lorg/joml/Matrix4f;FF)Lme/cortex/voxy/client/core/rendering/Viewport;"),
            index = 1, require = 0)
    private float lodgen$mapNearPlaneForViewport(float near) {
        return MapCamera.near() > 0 ? MapCamera.near() : near;
    }

    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
    private void lodgen$holdBackForMap(CallbackInfo info) {
        ShaderMap.renderHookSeen = true;
        if (ShaderMap.holdBackVoxy()) {
            info.cancel();
        }
    }
}
