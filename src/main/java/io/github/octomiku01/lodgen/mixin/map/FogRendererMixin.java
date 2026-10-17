package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No fog on the 3D map; Voxy takes its fog from the same values, so its LODs stay clear too */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void lodgen$noMapFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darken, ClientLevel level,
                                 CallbackInfoReturnable<FogData> info) {
        if (MapCamera.isActive()) {
            FogData fog = info.getReturnValue();
            fog.environmentalStart = 1.0e9f;
            fog.environmentalEnd = 2.0e9f;
            fog.renderDistanceStart = 1.0e9f;
            fog.renderDistanceEnd = 2.0e9f;
        }
    }
}
