package io.github.octomiku01.lodgen.mixin.map;

import io.github.octomiku01.lodgen.client.map.MapCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the 3D map is open the camera sits high above the map, before the frustum and projection are set up from it */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;
    @Shadow
    private float fov;
    @Shadow
    private float hudFov;
    @Shadow
    private float depthFar;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;hudFov:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void lodgen$mapCamera(DeltaTracker deltaTracker, CallbackInfo info) {
        if (!MapCamera.isActive()) {
            return;
        }
        this.detached = true;
        this.fov = MapCamera.fov();
        this.hudFov = MapCamera.fov();
        this.depthFar = Math.max(this.depthFar, MapCamera.far());
        this.setPosition(MapCamera.x(), MapCamera.y(), MapCamera.z());
        this.setRotation(MapCamera.yaw(), MapCamera.pitch());
    }
}
