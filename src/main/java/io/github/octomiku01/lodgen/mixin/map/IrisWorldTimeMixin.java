package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A shader pack keeps the real time while the 3D map is open. Over a shader pack the map is drawn by Voxy alone,
 * so the pack's time is not seen there; Iris hands it on only once per tick, and while the map pauses the game there
 * are none: after closing the map the pack's clouds went on with the map's time of day, then moved over.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.WorldTimeUniforms", remap = false)
public abstract class IrisWorldTimeMixin {
    @Inject(method = {"getWorldDayTime", "getWorldDay"}, at = @At("HEAD"), require = 0)
    private static void lodgen$realTimeStart(CallbackInfoReturnable<Integer> info) {
        MapCamera.readingRealTime = true;
    }

    @Inject(method = {"getWorldDayTime", "getWorldDay"}, at = @At("RETURN"), require = 0)
    private static void lodgen$realTimeEnd(CallbackInfoReturnable<Integer> info) {
        MapCamera.readingRealTime = false;
    }
}
