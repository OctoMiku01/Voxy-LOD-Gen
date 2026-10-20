package io.github.octomiku01.lodgen.mixin.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.ShaderMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No hand in front of the 3D map; over a shader pack, the map is drawn right before the GUI; in the Nether, overlays stay on top */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void lodgen$mapBeforeGui(CallbackInfo info) {
        if (MapCamera.isActive() && FabricLoader.getInstance().isModLoaded("voxy")) {
            ShaderMap.beforeGui(this.minecraft);
        }
        // With the Nether's near plane far out, the map's depth is close to the near end. Overlays drawn right before
        // the GUI against it, such as Xaero's waypoints, would end up under the map: nothing needs that depth anymore
        if (MapCamera.near() > 0 && this.minecraft.gameRenderer.mainRenderTarget().getDepthTexture() != null) {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(this.minecraft.gameRenderer.mainRenderTarget().getDepthTexture(), 0.0);
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void lodgen$noHandOnMap(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView, CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }
}
