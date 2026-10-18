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
    /** In the Nether the near plane cuts the roof off (see {@link #near}); 0 elsewhere */
    private static float near;
    /** Voxy's near plane on the map, see {@link #voxyNear} */
    private static float voxyNear;
    /** Set whenever Voxy takes the near plane from here, so it is known that the hook into Voxy took hold */
    public static volatile boolean voxyNearSeen;
    /** While set, the clocks give the real time, also on the map (for a shader pack's clock, see IrisWorldTimeMixin) */
    public static boolean readingRealTime;

    private MapCamera() {
    }

    static void update(Minecraft minecraft, double centerX, double centerY, double centerZ, double yawRadians, double pitchControl, double blocksPerPixel) {
        if (minecraft.level == null || minecraft.player == null) {
            active = false;
            return;
        }
        boolean end = minecraft.level.dimension() == net.minecraft.world.level.Level.END;
        boolean nether = minecraft.level.dimension() == net.minecraft.world.level.Level.NETHER;
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

        if (nether) {
            // Straight down, so the near plane lies flat at centerY: everything above it, the roof, is cut off
            pitchDegrees = 90.0f;
            near = (float) (range - 0.5);
        } else {
            near = 0;
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
        // Nothing is above the top of the world, and the camera is far above it: the near plane can go almost down
        // there. Close to 0, as Voxy has it while it draws the real chunks too, its depth is far too coarse from up
        // here, and faces behind show through as fine lines
        double aboveWorld = y - (minecraft.level.getMaxY() + 1);
        voxyNear = nether ? near : (float) Math.max(0.1, aboveWorld * 0.25);
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

    /**
     * The near plane: what is closer to the camera is not drawn. In the Nether it lies flat at the cut height; 0
     * elsewhere, where the game's own is kept.
     */
    public static float near() {
        return active ? near : 0;
    }

    /** Voxy's near plane: in the Nether the one that cuts the roof off, elsewhere as far out as nothing is cut */
    public static float voxyNear() {
        return active ? voxyNear : 0;
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
