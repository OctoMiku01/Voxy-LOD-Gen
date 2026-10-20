package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No clouds or weather between the camera of the 3D map and the ground; drawn once per pass, or per stage with OIT */
@Mixin({CloudRenderer.class, WeatherEffectRenderer.class})
public abstract class CloudsAndWeatherMixin {
    @Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void lodgen$nothingInTheWay(CallbackInfo info) {
        if (MapCamera.isActive()) {
            info.cancel();
        }
    }
}
