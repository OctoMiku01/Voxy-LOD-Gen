package io.github.octomiku01.lodgen.mixin.voxy;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy finds the visible LOD sections by drawing their boxes against the depth buffer, with back faces culled. When
 * the 3D map cuts the Nether open, the near plane takes off the top of the boxes right below the camera, and their
 * walls are seen from inside: nothing of them is left, and the sections there were never drawn. While cut open, the
 * walls count from inside too.
 */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class SectionCullMixin {
    @Unique
    private boolean lodgen$cullWasOn;

    @Inject(method = "buildDrawCalls(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL43;glDrawElementsIndirect(IIJ)V"), require = 0)
    private void lodgen$bothSidesWhenCut(CallbackInfo info) {
        this.lodgen$cullWasOn = MapCamera.near() > 0 && GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        if (this.lodgen$cullWasOn) {
            GL11C.glDisable(GL11C.GL_CULL_FACE);
        }
    }

    @Inject(method = "buildDrawCalls(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL43;glDrawElementsIndirect(IIJ)V", shift = At.Shift.AFTER), require = 0)
    private void lodgen$cullAgain(CallbackInfo info) {
        if (this.lodgen$cullWasOn) {
            GL11C.glEnable(GL11C.GL_CULL_FACE);
            this.lodgen$cullWasOn = false;
        }
    }
}
