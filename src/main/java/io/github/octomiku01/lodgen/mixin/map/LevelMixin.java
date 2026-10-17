package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.MapSettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The 3D map shows the time of day chosen on it, and no rain */
@Mixin(Level.class)
public abstract class LevelMixin {
    private boolean lodgen$onMap() {
        return MapCamera.isActive() && (Object) this instanceof ClientLevel;
    }

    private boolean lodgen$mapTime() {
        return this.lodgen$onMap() && MapSettings.get().timeOfDay() != MapSettings.TimeOfDay.REAL;
    }

    @Inject(method = "isDarkOutside", at = @At("HEAD"), cancellable = true)
    private void lodgen$mapDarkOutside(CallbackInfoReturnable<Boolean> info) {
        if (this.lodgen$mapTime()) {
            info.setReturnValue(MapSettings.get().timeOfDay().darkOutside);
        }
    }

    @Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true)
    private void lodgen$mapSkyDarken(CallbackInfoReturnable<Integer> info) {
        if (this.lodgen$mapTime()) {
            info.setReturnValue(MapSettings.get().timeOfDay().skyDarken);
        }
    }

    @Inject(method = {"getRainLevel", "getThunderLevel"}, at = @At("HEAD"), cancellable = true)
    private void lodgen$noMapRain(float partialTick, CallbackInfoReturnable<Float> info) {
        if (this.lodgen$onMap()) {
            info.setReturnValue(0.0f);
        }
    }

    @Inject(method = {"isRaining", "isThundering"}, at = @At("HEAD"), cancellable = true)
    private void lodgen$notRainingOnMap(CallbackInfoReturnable<Boolean> info) {
        if (this.lodgen$onMap()) {
            info.setReturnValue(false);
        }
    }
}
