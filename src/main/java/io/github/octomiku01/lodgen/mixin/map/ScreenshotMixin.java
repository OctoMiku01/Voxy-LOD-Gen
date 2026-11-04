package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F2 on the 3D map takes the map alone, without its panels and markers, see MapScreen#takeCleanScreenshot */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @Inject(method = "grab(Lnet/minecraft/client/Minecraft;Z)V", at = @At("HEAD"), cancellable = true)
    private static void lodgen$cleanMapScreenshot(Minecraft minecraft, boolean panorama, CallbackInfo info) {
        if (!panorama && MapScreen.takeCleanScreenshot(minecraft)) {
            info.cancel();
        }
    }
}
