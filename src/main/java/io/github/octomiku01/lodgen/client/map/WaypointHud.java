package io.github.octomiku01.lodgen.client.map;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The own waypoints in the game: a diamond in its colour where it is on the screen, with its name and distance. Found
 * from the camera's place, direction and field of view, like the game projects the world; the view bobbing is left
 * out, which moves them by a few pixels at most. Render thread only.
 */
public final class WaypointHud {
    private WaypointHud() {
    }

    /** Called after the game's HUD, see HudMixin; @param hidden the HUD is hidden (F1) */
    public static void draw(Minecraft minecraft, GuiGraphicsExtractor g, boolean hidden) {
        if (hidden || minecraft.player == null || minecraft.level == null || !MapSettings.get().waypointsInGame()) {
            return;
        }
        var waypoints = Waypoints.current(minecraft);
        if (waypoints.isEmpty()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        double yaw = Math.toRadians(camera.yRot());
        double pitch = Math.toRadians(camera.xRot());
        // Where the camera looks, its right and its up, as the game turns them
        double forwardX = -Math.sin(yaw) * Math.cos(pitch);
        double forwardY = -Math.sin(pitch);
        double forwardZ = Math.cos(yaw) * Math.cos(pitch);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double upX = -Math.sin(yaw) * Math.sin(pitch);
        double upY = Math.cos(pitch);
        double upZ = Math.cos(yaw) * Math.sin(pitch);
        int width = g.guiWidth();
        int height = g.guiHeight();
        double tanY = Math.tan(Math.toRadians(camera.getFov()) / 2.0);
        double tanX = tanY * width / (double) height;
        for (Waypoints.Waypoint waypoint : waypoints) {
            // Just above the block it marks
            double dx = waypoint.x() + 0.5 - eye.x;
            double dy = waypoint.y() + 1.0 - eye.y;
            double dz = waypoint.z() + 0.5 - eye.z;
            double depth = dx * forwardX + dy * forwardY + dz * forwardZ;
            if (depth < 0.5) {
                continue;
            }
            double ndcX = (dx * rightX + dz * rightZ) / (depth * tanX);
            double ndcY = (dx * upX + dy * upY + dz * upZ) / (depth * tanY);
            if (Math.abs(ndcX) > 1.1 || Math.abs(ndcY) > 1.1) {
                continue;
            }
            int x = (int) Math.round((1.0 + ndcX) * width / 2.0);
            int y = (int) Math.round((1.0 - ndcY) * height / 2.0);
            int distance = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
            drawDiamond(g, x, y, 4, waypoint.color());
            Component label = Component.literal(waypoint.name() + "  " + distance + " m");
            int labelWidth = minecraft.font.width(label);
            g.fill(x - labelWidth / 2 - 2, y - 16, x + labelWidth / 2 + 2, y - 6, 0x88000000);
            g.centeredText(minecraft.font, label, x, y - 15, waypoint.color());
        }
    }

    /** A diamond with a dark edge, centred on the point; also used on the map */
    static void drawDiamond(GuiGraphicsExtractor g, int x, int y, int half, int color) {
        for (int row = -half - 1; row <= half + 1; row++) {
            int edge = half + 1 - Math.abs(row);
            g.fill(x - edge, y + row, x + edge + 1, y + row + 1, 0xCC000000);
        }
        for (int row = -half; row <= half; row++) {
            int span = half - Math.abs(row);
            g.fill(x - span, y + row, x + span + 1, y + row + 1, color);
        }
    }
}
