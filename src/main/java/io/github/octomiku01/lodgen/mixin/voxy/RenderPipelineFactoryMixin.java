package io.github.octomiku01.lodgen.mixin.voxy;

import io.github.octomiku01.lodgen.client.map.ShaderMap;
import me.cortex.voxy.client.core.RenderPipelineFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The 3D map over a shader pack draws Voxy alone, with Voxy's own pipeline instead of the pack's. Takes none of the
 * method's parameters, so a change to them does not break it; if the method itself is gone, {@link ShaderMap} notices.
 */
@Mixin(value = RenderPipelineFactory.class, remap = false)
public abstract class RenderPipelineFactoryMixin {
    @Inject(method = "createIrisPipeline", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lodgen$voxyPipelineForMap(CallbackInfoReturnable<Object> info) {
        ShaderMap.pipelineHookSeen = true;
        if (ShaderMap.forceVoxyPipeline()) {
            info.setReturnValue(null);
        }
    }
}
