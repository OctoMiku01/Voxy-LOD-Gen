package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import io.github.octomiku01.lodgen.client.map.MapSettings;
import java.util.Map;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sky colour, sun, moon and light are worked out once per tick, by the level and again here, blended towards the
 * next, and while the map pauses the game there are no ticks: after closing the map the world kept the map's time of
 * day, then faded over. Worked out again right away when the map opens or closes, or its time of day changes.
 */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class EnvironmentAttributeProbeMixin {
    @Shadow
    @Final
    private Map<EnvironmentAttribute<?>, ?> valueProbes;
    @Shadow
    private Level level;
    @Unique
    private MapSettings.TimeOfDay lodgen$lastMapTime;

    @Inject(method = "getValue", at = @At("HEAD"))
    private <Value> void lodgen$readAgainForMap(EnvironmentAttribute<Value> attribute, float partialTicks, CallbackInfoReturnable<Value> info) {
        MapSettings.TimeOfDay mapTime = MapCamera.isActive() ? MapSettings.get().timeOfDay() : null;
        if (mapTime != this.lodgen$lastMapTime) {
            this.lodgen$lastMapTime = mapTime;
            this.valueProbes.clear();
            if (this.level != null) {
                this.level.environmentAttributes().invalidateTickCache();
            }
        }
    }
}
