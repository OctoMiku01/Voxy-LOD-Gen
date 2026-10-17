package io.github.octomiku01.lodgen.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Where the game's camera goes while the 3D map is open: high above the point the map looks at, tilted down. The
 * world is rendered from there as usual, so Voxy draws its LODs from above. The mixins in
 * {@code io.github.octomiku01.lodgen.mixin.map} move the camera and hide what does not belong on a map.
 * <p>
 * Adapted from 3D Maps Voxy Addon by raben12paste (MIT, see THIRD_PARTY_NOTICES).
 */
public final class MapCamera {
    private static boolean active;
    private static double x;
    private static double y;
    private static double z;
    private static float yaw;
    private static float pitch;
    private static float fov;
    private static float far;
    private static double range;

    private MapCamera() {
    }

    static void update(Minecraft minecraft, double centerX, double centerY, double centerZ, double yawRadians, double pitchControl, double blocksPerPixel) {
        if (minecraft.level == null || minecraft.player == null) {
            active = false;
            return;
        }
        boolean end = minecraft.level.dimension() == net.minecraft.world.level.Level.END;
        float pitchDegrees = Mth.clamp((float) (18.0 + pitchControl * 42.0), 18.0f, 72.0f);
        double desiredRange = Mth.clamp(blocksPerPixel * 210.0, 64.0, 8192.0);
        double range;
        if (end) {
            pitchDegrees = Mth.clamp((float) (28.0 + pitchControl * 34.0), 30.0f, 62.0f);
            range = Math.max(512.0, desiredRange);
            fov = Mth.clamp((float) (70.0 * desiredRange / range), 14.0f, 70.0f);
        } else {
            // Zooming in narrows the view instead of coming closer: from too low the terrain hides itself
            range = Math.max(1024.0, desiredRange);
            fov = Mth.clamp((float) (70.0 * desiredRange / range), 6.0f, 70.0f);
        }

        MapCamera.range = range;
        double pitchRadians = Math.toRadians(pitchDegrees);
        double horizontal = Math.cos(pitchRadians) * range;
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        x = centerX - forwardX * horizontal;
        y = centerY + Math.sin(pitchRadians) * range;
        z = centerZ - forwardZ * horizontal;
        yaw = (float) Math.toDegrees(yawRadians);
        pitch = pitchDegrees;
        // The game's far plane follows its own view distance, which is far too close from up here
        far = (float) (range * 3.0);
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    static void deactivate() {
        active = false;
    }

    public static double x() {
        return x;
    }

    public static double y() {
        return y;
    }

    public static double z() {
        return z;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static float fov() {
        return fov;
    }

    public static float far() {
        return far;
    }

    /** How far the camera is from the point it looks at */
    static double range() {
        return range;
    }

    /** How far from the camera, horizontally, the map shows: to where it looks, and as far again beyond */
    static double reach() {
        return Math.cos(Math.toRadians(pitch)) * range + range;
    }
}
