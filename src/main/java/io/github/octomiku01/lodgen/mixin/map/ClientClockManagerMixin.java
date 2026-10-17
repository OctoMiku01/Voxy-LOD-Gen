package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.MapSettings;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sky, sun and light follow the clocks: on the 3D map they show the time of day chosen there */
@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void lodgen$mapTime(Holder<WorldClock> clock, CallbackInfoReturnable<Long> info) {
        if (MapCamera.isActive() && MapSettings.get().timeOfDay() != MapSettings.TimeOfDay.REAL) {
            info.setReturnValue(MapSettings.get().timeOfDay().ticks);
        }
    }
}
