package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.MapSettings;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lightmap is made again only after a tick, and while the map pauses the game there are none: after closing the
 * map the world kept the map's time of day in its light until the game ran again. Made again right away when the
 * map opens or closes, or its time of day changes.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    @Shadow
    private boolean needsUpdate;
    @Unique
    private MapSettings.TimeOfDay lodgen$lastMapTime;

    @Inject(method = "extract", at = @At("HEAD"))
    private void lodgen$updateForMap(LightmapRenderState state, float partialTick, CallbackInfo info) {
        MapSettings.TimeOfDay mapTime = MapCamera.isActive() ? MapSettings.get().timeOfDay() : null;
        if (mapTime != this.lodgen$lastMapTime) {
            this.lodgen$lastMapTime = mapTime;
            this.needsUpdate = true;
        }
    }
}
