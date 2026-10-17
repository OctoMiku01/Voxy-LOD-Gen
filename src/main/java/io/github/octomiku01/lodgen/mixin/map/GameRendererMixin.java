package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.ShaderMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No hand in front of the 3D map; over a shader pack, the map is drawn right before the GUI */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void lodgen$mapBeforeGui(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo info) {
        if (MapCamera.isActive() && FabricLoader.getInstance().isModLoaded("voxy")) {
            ShaderMap.beforeGui(this.minecraft);
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void lodgen$noHandOnMap(CameraRenderState cameraState, float partialTick, Matrix4fc modelView, CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }
}
