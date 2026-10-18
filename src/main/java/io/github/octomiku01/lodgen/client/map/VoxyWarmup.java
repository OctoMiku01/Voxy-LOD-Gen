package io.github.octomiku01.lodgen.client.map;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * A Voxy renderer that was just made, when a world starts or after the 3D map over a shader pack, starts out with its
 * coarsest LODs and refines them over the next frames: up close those stick out of the world as huge blocks. For a
 * moment after it first draws, Voxy leaves out what is within the view distance, where the game draws the real
 * chunks. On the map its own near plane counts instead.
 * <p>
 * Render thread only.
 */
public final class VoxyWarmup {
    private static final long WARMUP_NANOS = 3_000_000_000L;
    /** Room for a wider view than the setting, from sprinting or speed */
    private static final double FOV_MARGIN = 1.3;

    private static boolean pending;
    private static long untilNanos;

    private VoxyWarmup() {
    }

    public static void rendererCreated() {
        pending = true;
        untilNanos = 0L;
    }

    /** The near plane Voxy needs now, 0 if none */
    public static float nearPlane() {
        if (pending) {
            pending = false;
            untilNanos = System.nanoTime() + WARMUP_NANOS;
        }
        if (untilNanos == 0L) {
            return 0;
        }
        if (System.nanoTime() - untilNanos > 0) {
            untilNanos = 0L;
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double aspect = window.getWidth() / (double) Math.max(1, window.getHeight());
        double halfFov = Math.toRadians(Math.min(170.0, minecraft.options.fov().get() * FOV_MARGIN)) / 2;
        // A plane, not a sphere: towards the corners of the screen it comes closer, so it is pulled in to stay inside
        // the view distance there too
        double corner = Math.atan(Math.tan(halfFov) * Math.sqrt(1 + aspect * aspect));
        return (float) (minecraft.options.getEffectiveRenderDistance() * 16 * Math.cos(corner));
    }
}
