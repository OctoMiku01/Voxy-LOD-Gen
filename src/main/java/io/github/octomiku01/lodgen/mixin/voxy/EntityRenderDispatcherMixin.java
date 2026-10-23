package io.github.octomiku01.lodgen.mixin.voxy;

import io.github.octomiku01.lodgen.client.LodOcclusion;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Real players the game draws itself can stand where only LODs show the terrain (at the edge of the loaded chunks),
 * and with a shader pack they would show through it: see {@link LodOcclusion}. Here only because it needs Voxy.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void lodgen$hiddenBehindLods(E entity, Frustum frustum, double cameraX, double cameraY, double cameraZ, float partialTicks,
                                                           CallbackInfoReturnable<Boolean> info) {
        if (entity instanceof Player && LodOcclusion.hidden(entity.getUUID())) {
            info.setReturnValue(false);
        }
    }
}
