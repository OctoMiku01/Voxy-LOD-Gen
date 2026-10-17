package io.github.octomiku01.lodgen.mixin.voxy;

import io.github.octomiku01.lodgen.client.map.ShaderMap;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 3D map over a shader pack draws Voxy alone, after the world: inside the world it is held back. Takes none of
 * the method's parameters, so a change to them does not break it; if the method itself is gone, {@link ShaderMap}
 * notices.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin {
    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
    private void lodgen$holdBackForMap(CallbackInfo info) {
        ShaderMap.renderHookSeen = true;
        if (ShaderMap.holdBackVoxy()) {
            info.cancel();
        }
    }
}
