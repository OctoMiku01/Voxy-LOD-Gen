package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.MapSettings;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sky, sun and light follow the clocks: on the 3D map they show the time of day chosen there */
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClientClockManagerMixin {
    @Inject(method = "totalTicks", at = @At("HEAD"), cancellable = true)
    private void lodgen$mapTime(CallbackInfoReturnable<Long> info) {
        if (MapCamera.isActive() && !MapCamera.readingRealTime && MapSettings.get().timeOfDay() != MapSettings.TimeOfDay.REAL) {
            info.setReturnValue(MapSettings.get().timeOfDay().ticks);
        }
    }
}
