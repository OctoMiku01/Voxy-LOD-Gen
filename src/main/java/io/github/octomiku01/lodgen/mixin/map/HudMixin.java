package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.WaypointHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No crosshair, hotbar, chat, F3 screen... over the 3D map; the own waypoints over the game */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void lodgen$noHudOnMap(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }

    // The own waypoints, over the game but under the screens
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void lodgen$waypoints(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        WaypointHud.draw(Minecraft.getInstance(), graphics, ((Hud) (Object) this).isHidden());
    }

    // Drawn on its own, over the screen
    @Inject(method = "extractDebugOverlay", at = @At("HEAD"), cancellable = true)
    private void lodgen$noDebugOverlayOnMap(GuiGraphicsExtractor graphics, CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }
}
