package io.github.octomiku01.lodgen.client.map;

import io.github.octomiku01.lodgen.MapFocusPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import io.github.octomiku01.lodgen.client.FarEntities;
import io.github.octomiku01.lodgen.MapPlayersPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

/**
 * The 3D map: a see-through screen over the world, which {@link MapCamera} renders from high above. Drag to move,
 * wheel to zoom toward the cursor, middle button to turn.
 * <p>
 * Adapted from 3D Maps Voxy Addon by raben12paste (MIT, see THIRD_PARTY_NOTICES), from its Minecraft 1.21.11 version.
 */
public class MapScreen extends Screen {
    private static final long OPEN_ANIMATION_NANOS = 850_000_000L;
    /** The height the map is laid out at: dragging and zooming keep points of this plane under the mouse */
    private static final double MAP_VIEW_LEVEL = 320.0;
    private static final double MAX_KEYBOARD_CAMERA_SPEED = 640.0;
    private static final double MAX_DRAG_STEP_BLOCKS = 1536.0;
    private static final int SETTINGS_ROW_HEIGHT = 24;
    private static final int SPEED_BUTTON_SIZE = 18;
    private static final int HUD_MARGIN = 12;
    private static final int HUD_GAP = 6;
    private static final int HUD_BUTTON_HEIGHT = 24;
    private static final int KEY_HINT_HEIGHT = 22;
    private static final int BG = 0xFF080810;
    /** Where the map looks is sent this often, and again right away once it moved this many chunks */
    private static final int FOCUS_INTERVAL_TICKS = 20;
    private static final int FOCUS_MOVE_CHUNKS = 2;
    private static final int CUT_SLIDER_WIDTH = 156;
    private static final int CUT_SLIDER_HEIGHT = 36;
    /** Frames in the Nether to wait for the hook into Voxy's near plane before giving up on the Nether */
    private static final int NEAR_HOOK_FRAMES = 40;
    /** Where the Nether is cut open, kept between openings of the map */
    private static int netherCut = 120;
    /** Voxy could not be cut open: the Nether stays closed, its roof would hide everything */
    private static boolean netherBroken;

    private final KeyMapping openKey;
    /** init runs again on every resize */
    private boolean opened;
    private double viewCenterX;
    private double viewCenterY;
    private double viewCenterZ;
    private double blocksPerPixel = 4.0;
    private double viewYaw = Math.toRadians(45.0);
    private double viewPitch = 0.68;

    private boolean dragging;
    private boolean middleDragging;
    private double dragStartMouseX;
    private double dragStartYaw;
    private double dragLastMouseX;
    private double dragLastMouseY;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private double moveVelocityX;
    private double moveVelocityZ;
    private long lastFrameNanos;
    private long openedAtNanos;
    private boolean settingsOpen;
    private int settingsButtonX = -1;
    private int settingsButtonY = -1;
    private int settingsButtonW;
    private int settingsButtonH;
    private int settingsPanelX = -1;
    private int settingsPanelY = -1;
    private int settingsPanelW;
    private int settingsPanelH;
    private int speedMinusX = -1;
    private int speedMinusY = -1;
    private int speedPlusX = -1;
    private int speedPlusY = -1;
    private int pauseRowY = -1;
    private int speedRowY = -1;
    private int timeRowY = -1;
    private int lodsRowY = -1;
    private int focusTicks;
    private int cutSliderX = -1;
    private int cutSliderY = -1;
    private boolean cutDragging;
    private int netherFrames;
    private int sentFocusX = Integer.MIN_VALUE;
    private int sentFocusZ;
    private int sentFocusRadius;

    public MapScreen(KeyMapping openKey) {
        super(Component.translatable("lodgen.map.title"));
        this.openKey = openKey;
    }

    @Override
    protected void init() {
        super.init();
        openedAtNanos = System.nanoTime();
        lastFrameNanos = 0L;
        viewCenterY = MAP_VIEW_LEVEL;
        if (minecraft.player != null) {
            viewCenterX = minecraft.player.getX();
            viewCenterZ = minecraft.player.getZ();
        }
        syncWorldCamera();
        if (!opened) {
            opened = true;
            ShaderMap.open(minecraft);
            if (MapSettings.get().onlyLods()) {
                VoxyRange.ingestLoadedChunks(minecraft);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (isUnsupportedDimension()) {
            MapCamera.deactivate();
            drawUnsupportedDimension(g);
            drawOpeningAnimation(g);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }
        updateSmoothControls();
        syncWorldCamera();
        checkNetherCut();
        drawPlayers(g, delta);
        drawWorldViewerOverlay(g, mouseX, mouseY);
        drawOpeningAnimation(g);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // See-through: the world behind is the map
    }

    @Override
    public void tick() {
        syncWorldCamera();
        sendFocus();
    }

    /** Tells the server where the map looks, so the LODs there are made first */
    private void sendFocus() {
        if (isUnsupportedDimension() || !MapCamera.isActive() || !ClientPlayNetworking.canSend(MapFocusPayload.TYPE)) {
            return;
        }
        int chunkX = Mth.floor(viewCenterX) >> 4;
        int chunkZ = Mth.floor(viewCenterZ) >> 4;
        // About half of what the screen shows across
        double halfWidth = MapCamera.range() * Math.tan(Math.toRadians(MapCamera.fov()) / 2) * width / Math.max(1, height);
        int radius = Math.max(4, (int) Math.ceil(halfWidth / 16));
        boolean moved = sentFocusX == Integer.MIN_VALUE || Math.max(Math.abs(chunkX - sentFocusX), Math.abs(chunkZ - sentFocusZ)) >= FOCUS_MOVE_CHUNKS
                || radius != sentFocusRadius;
        if (!moved && ++focusTicks < FOCUS_INTERVAL_TICKS) {
            return;
        }
        focusTicks = 0;
        sentFocusX = chunkX;
        sentFocusZ = chunkZ;
        sentFocusRadius = radius;
        ClientPlayNetworking.send(new MapFocusPayload(chunkX, chunkZ, radius));
    }

    private double openingProgress() {
        if (openedAtNanos == 0L) {
            return 1.0;
        }
        double raw = Mth.clamp((System.nanoTime() - openedAtNanos) / (double) OPEN_ANIMATION_NANOS, 0.0, 1.0);
        return 1.0 - Math.pow(1.0 - raw, 3.0);
    }

    private void updateSmoothControls() {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 1.0 / 60.0 : Math.min(0.08, (now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;

        long window = minecraft.getWindow().handle();
        double forwardInput = keyDown(window, GLFW.GLFW_KEY_W) || keyDown(window, GLFW.GLFW_KEY_UP) ? 1.0 : 0.0;
        forwardInput -= keyDown(window, GLFW.GLFW_KEY_S) || keyDown(window, GLFW.GLFW_KEY_DOWN) ? 1.0 : 0.0;
        double strafeInput = keyDown(window, GLFW.GLFW_KEY_A) || keyDown(window, GLFW.GLFW_KEY_LEFT) ? 1.0 : 0.0;
        strafeInput -= keyDown(window, GLFW.GLFW_KEY_D) || keyDown(window, GLFW.GLFW_KEY_RIGHT) ? 1.0 : 0.0;
        double horizontalInput = Math.hypot(forwardInput, strafeInput);
        if (horizontalInput > 1.0) {
            forwardInput /= horizontalInput;
            strafeInput /= horizontalInput;
        }

        double speedBoost = keyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || keyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT) ? 2.5 : 1.0;
        double moveSpeed = Math.max(12.0, blocksPerPixel * 76.0) * speedBoost * MapSettings.get().cameraSpeed;
        moveSpeed = Math.min(moveSpeed, MAX_KEYBOARD_CAMERA_SPEED * speedBoost);
        double forwardX = -Math.sin(viewYaw);
        double forwardZ = Math.cos(viewYaw);
        double rightX = Math.cos(viewYaw);
        double rightZ = Math.sin(viewYaw);
        double targetVX = dragging ? 0.0 : (forwardX * forwardInput + rightX * strafeInput) * moveSpeed;
        double targetVZ = dragging ? 0.0 : (forwardZ * forwardInput + rightZ * strafeInput) * moveSpeed;

        double response = 1.0 - Math.exp(-dt * 10.0);
        moveVelocityX = Mth.lerp(response, moveVelocityX, targetVX);
        moveVelocityZ = Mth.lerp(response, moveVelocityZ, targetVZ);
        if (!dragging) {
            viewCenterX += moveVelocityX * dt;
            viewCenterZ += moveVelocityZ * dt;
        }
    }

    private static boolean keyDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    /** The point of the map plane under the mouse */
    private GroundPoint mapPointAt(double mouseX, double mouseY) {
        if (width <= 0 || height <= 0 || !MapCamera.isActive()) {
            return null;
        }
        double pitch = Math.toRadians(MapCamera.pitch());
        double forwardX = -Math.sin(viewYaw) * Math.cos(pitch);
        double forwardY = -Math.sin(pitch);
        double forwardZ = Math.cos(viewYaw) * Math.cos(pitch);
        double rightX = Math.cos(viewYaw);
        double rightZ = Math.sin(viewYaw);
        double upX = -Math.sin(viewYaw) * Math.sin(pitch);
        double upY = Math.cos(pitch);
        double upZ = Math.cos(viewYaw) * Math.sin(pitch);

        double tanY = Math.tan(Math.toRadians(MapCamera.fov()) * 0.5);
        double tanX = tanY * width / (double) height;
        double ndcX = 1.0 - mouseX / width * 2.0;
        double ndcY = 1.0 - mouseY / height * 2.0;
        double rayX = forwardX + rightX * ndcX * tanX + upX * ndcY * tanY;
        double rayY = forwardY + upY * ndcY * tanY;
        double rayZ = forwardZ + rightZ * ndcX * tanX + upZ * ndcY * tanY;
        if (Math.abs(rayY) < 1.0E-6) {
            return null;
        }
        double t = (viewCenterY - MapCamera.y()) / rayY;
        if (t <= 0.0 || !Double.isFinite(t)) {
            return null;
        }
        return new GroundPoint(MapCamera.x() + rayX * t, MapCamera.z() + rayZ * t);
    }

    /** Where a point of the world is on the screen, or null when it is behind the camera */
    private double[] screenPointOf(Vec3 point) {
        double pitch = Math.toRadians(MapCamera.pitch());
        double dx = point.x - MapCamera.x();
        double dy = point.y - MapCamera.y();
        double dz = point.z - MapCamera.z();
        double depth = -Math.sin(viewYaw) * Math.cos(pitch) * dx - Math.sin(pitch) * dy + Math.cos(viewYaw) * Math.cos(pitch) * dz;
        if (depth < 1.0) {
            return null;
        }
        double tanY = Math.tan(Math.toRadians(MapCamera.fov()) * 0.5);
        double tanX = tanY * width / (double) height;
        // As in mapPointAt: x counts to the left, y upward
        double ndcX = (Math.cos(viewYaw) * dx + Math.sin(viewYaw) * dz) / (depth * tanX);
        double ndcY = (-Math.sin(viewYaw) * Math.sin(pitch) * dx + Math.cos(pitch) * dy + Math.cos(viewYaw) * Math.sin(pitch) * dz) / (depth * tanY);
        return new double[] {(1.0 - ndcX) * width / 2.0, (1.0 - ndcY) * height / 2.0};
    }

    /**
     * Every player, with their face and name; this player in gold, on top. Those the client has (near, or far on the
     * LODs) move smoothly; the server tells where the others are once a second.
     */
    private void drawPlayers(GuiGraphicsExtractor g, float partialTick) {
        if (minecraft.level == null) {
            return;
        }
        Set<UUID> shown = new HashSet<>();
        List<AbstractClientPlayer> players = new ArrayList<>();
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player != minecraft.player && !player.isSpectator()) {
                players.add(player);
            }
        }
        players.addAll(FarEntities.players());
        for (AbstractClientPlayer player : players) {
            shown.add(player.getUUID());
            drawMarker(g, player.getPosition(partialTick), player.getSkin(), player.getName(), false);
        }
        for (MapPlayersPayload.Entry entry : MapPlayers.current(minecraft)) {
            if (shown.contains(entry.uuid()) || minecraft.player != null && entry.uuid().equals(minecraft.player.getUUID())) {
                continue;
            }
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(entry.uuid());
            PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(entry.uuid());
            drawMarker(g, new Vec3(entry.x(), entry.y(), entry.z()), skin, Component.literal(entry.name()), false);
        }
        if (minecraft.player != null) {
            drawMarker(g, minecraft.player.getPosition(partialTick), minecraft.player.getSkin(), minecraft.player.getName(), true);
        }
    }

    private void drawMarker(GuiGraphicsExtractor g, Vec3 position, PlayerSkin skin, Component name, boolean self) {
        double[] screen = screenPointOf(position);
        if (screen == null || screen[0] < -16 || screen[0] > width + 16 || screen[1] < -16 || screen[1] > height + 16) {
            return;
        }
        int x = (int) Math.round(screen[0]);
        int y = (int) Math.round(screen[1]);
        int size = self ? 12 : 10;
        g.fill(x - size / 2 - 1, y - size / 2 - 1, x + size / 2 + 1, y + size / 2 + 1, self ? 0xFFFFC23B : 0xFF7DEBFF);
        PlayerFaceExtractor.extractRenderState(g, skin, x - size / 2, y - size / 2, size);
        int nameWidth = minecraft.font.width(name);
        int nameY = y - size / 2 - 12;
        g.fill(x - nameWidth / 2 - 2, nameY - 1, x + nameWidth / 2 + 2, nameY + 9, 0x99030914);
        g.centeredText(minecraft.font, name, x, nameY, self ? 0xFFFFE08A : 0xFFE9F2FF);
    }

    private void keepMapPointUnderMouse(GroundPoint anchor, double mouseX, double mouseY) {
        GroundPoint current = mapPointAt(mouseX, mouseY);
        if (anchor == null || current == null) {
            return;
        }
        viewCenterX += anchor.x - current.x;
        viewCenterZ += anchor.z - current.z;
    }

    private void panByPixels(double deltaX, double deltaY) {
        double rightX = Math.cos(viewYaw);
        double rightZ = Math.sin(viewYaw);
        double forwardX = -Math.sin(viewYaw);
        double forwardZ = Math.cos(viewYaw);
        double forwardScale = 1.0 / Math.max(0.35, Math.sin(Math.toRadians(MapCamera.pitch())));
        double sideDistance = deltaX * blocksPerPixel;
        double forwardDistance = deltaY * blocksPerPixel * forwardScale;
        double distance = Math.hypot(sideDistance, forwardDistance);
        if (distance > MAX_DRAG_STEP_BLOCKS) {
            double scale = MAX_DRAG_STEP_BLOCKS / distance;
            sideDistance *= scale;
            forwardDistance *= scale;
        }
        viewCenterX += rightX * sideDistance + forwardX * forwardDistance;
        viewCenterZ += rightZ * sideDistance + forwardZ * forwardDistance;
    }

    private void syncWorldCamera() {
        if (isUnsupportedDimension()) {
            MapCamera.deactivate();
        } else {
            if (isNether()) {
                // The map plane is the cut: dragging and zooming keep its points under the mouse
                viewCenterY = netherCut;
            }
            MapCamera.update(minecraft, viewCenterX, viewCenterY, viewCenterZ, viewYaw, viewPitch, blocksPerPixel);
            VoxyRange.cover(MapCamera.reach());
        }
    }

    /** The Nether's roof would hide everything, unless Voxy can be cut open below it */
    private boolean isUnsupportedDimension() {
        return netherBroken && isNether();
    }

    private boolean isNether() {
        return minecraft != null && minecraft.level != null && minecraft.level.dimension() == Level.NETHER;
    }

    /** Voxy draws the roof unless the hook into its near plane took hold; it shows within a few frames */
    private void checkNetherCut() {
        if (!isNether() || MapCamera.voxyNearSeen || netherBroken) {
            return;
        }
        try {
            if (me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullable() == null) {
                return;
            }
        } catch (LinkageError e) {
            return;
        }
        if (++netherFrames > NEAR_HOOK_FRAMES) {
            netherBroken = true;
            MapCamera.deactivate();
            io.github.octomiku01.lodgen.LodGen.LOGGER.warn("Could not cut Voxy open below the Nether's roof, the 3D map stays closed there");
        }
    }

    private int cutMin() {
        return minecraft.level.dimensionType().minY();
    }

    private int cutMax() {
        return minecraft.level.dimensionType().minY() + minecraft.level.dimensionType().logicalHeight() - 1;
    }

    private void setCut(int y) {
        netherCut = Mth.clamp(y, cutMin(), cutMax());
        syncWorldCamera();
    }

    /** In the Nether: where the roof is cut off, from the bottom of the world up to just below the roof */
    private void drawCutSlider(GuiGraphicsExtractor g, int slide) {
        cutSliderX = width - CUT_SLIDER_WIDTH - HUD_MARGIN;
        cutSliderY = settingsButtonY + settingsButtonH + HUD_GAP;
        drawGlassPanel(g, cutSliderX, cutSliderY, CUT_SLIDER_WIDTH, CUT_SLIDER_HEIGHT, 0xAA030914, 0xFF3BA4FF);
        g.text(minecraft.font, Component.translatable("lodgen.map.cut", netherCut), cutSliderX + 10, cutSliderY + 5, 0xFFE9F2FF);
        int trackX = cutSliderX + 10;
        int trackW = CUT_SLIDER_WIDTH - 20;
        int trackY = cutSliderY + 24;
        g.fill(trackX, trackY - 1, trackX + trackW, trackY + 1, 0xFF3BA4FF);
        double share = (netherCut - cutMin()) / (double) Math.max(1, cutMax() - cutMin());
        int knobX = trackX + (int) Math.round(share * trackW);
        g.fill(knobX - 3, trackY - 5, knobX + 3, trackY + 5, cutDragging ? 0xFFFFFFFF : 0xFF7DEBFF);
    }

    private boolean isInsideCutSlider(double mouseX, double mouseY) {
        return isNether() && cutSliderX >= 0 && mouseX >= cutSliderX && mouseX <= cutSliderX + CUT_SLIDER_WIDTH
                && mouseY >= cutSliderY && mouseY <= cutSliderY + CUT_SLIDER_HEIGHT;
    }

    private void dragCut(double mouseX) {
        double share = Mth.clamp((mouseX - cutSliderX - 10) / (CUT_SLIDER_WIDTH - 20), 0.0, 1.0);
        setCut(cutMin() + (int) Math.round(share * (cutMax() - cutMin())));
    }

    private void drawUnsupportedDimension(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, height, BG);
        int boxW = Math.min(width - 40, 420);
        int boxH = 82;
        int x = (width - boxW) / 2;
        int y = (height - boxH) / 2;
        g.fill(x - 2, y - 2, x + boxW + 2, y + boxH + 2, 0x553BA4FF);
        g.fill(x, y, x + boxW, y + boxH, 0xEE030914);
        g.fill(x, y, x + 4, y + boxH, 0xFF3BA4FF);
        g.centeredText(minecraft.font, Component.translatable("lodgen.map.unsupported.title"), width / 2, y + 22, 0xFFFFFFFF);
        g.centeredText(minecraft.font, Component.translatable("lodgen.map.unsupported.nether"), width / 2, y + 46, 0xFFBFD8FF);
    }

    private void drawOpeningAnimation(GuiGraphicsExtractor g) {
        double progress = openingProgress();
        if (progress >= 0.995) {
            return;
        }
        int alpha = (int) (185 * (1.0 - progress));
        g.fill(0, 0, width, height, (alpha << 24) | 0x030914);

        int bandHeight = (int) ((height * 0.28) * (1.0 - progress));
        if (bandHeight > 0) {
            g.fill(0, 0, width, bandHeight, 0xDD030914);
            g.fill(0, height - bandHeight, width, height, 0xDD030914);
            g.fill(0, bandHeight, width, bandHeight + 2, 0xFF3BA4FF);
            g.fill(0, height - bandHeight - 2, width, height - bandHeight, 0xFF3BA4FF);
        }
        int scanY = (int) (height * progress);
        g.fill(0, scanY - 1, width, scanY + 1, 0xAA72D7FF);
        int titleAlpha = (int) (255 * (1.0 - Math.abs(progress - 0.35) / 0.35));
        if (titleAlpha > 0) {
            int color = (Mth.clamp(titleAlpha, 0, 255) << 24) | 0xDFF8FF;
            g.centeredText(minecraft.font, this.title, width / 2, height / 2 - 5, color);
        }
    }

    private void drawWorldViewerOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int slide = (int) ((1.0 - openingProgress()) * 28.0);
        int topX = HUD_MARGIN;
        int topY = HUD_MARGIN - slide;
        String playerText = "";
        if (minecraft.player != null) {
            playerText = Component.translatable("lodgen.map.player").getString() + "  " + (int) minecraft.player.getX() + " / "
                    + (int) minecraft.player.getY() + " / " + (int) minecraft.player.getZ();
        }
        String cameraText = Component.translatable("lodgen.map.camera").getString() + "  " + (int) viewCenterX + " / " + (int) viewCenterZ
                + "   " + Component.translatable("lodgen.map.zoom").getString() + " " + String.format("%.2f", blocksPerPixel);
        int textW = Math.max(minecraft.font.width(this.title), Math.max(minecraft.font.width(playerText), minecraft.font.width(cameraText)));
        int topW = Math.max(1, Math.min(width - HUD_MARGIN * 2, textW + 20));
        int topH = minecraft.player != null ? 42 : 30;
        drawGlassPanel(g, topX, topY, topW, topH, 0xAA030914, 0xFF3BA4FF);
        g.text(minecraft.font, this.title, topX + 10, topY + 5, 0xFFFFFFFF);
        if (minecraft.player != null) {
            g.text(minecraft.font, playerText, topX + 10, topY + 18, 0xFFDFF8FF);
        }
        g.text(minecraft.font, cameraText, topX + 10, topY + (minecraft.player != null ? 31 : 18), 0xFF9FD8FF);

        int footerTop = drawFooter(g, slide);
        drawSettingsButton(g, mouseX, mouseY, slide);
        if (isNether()) {
            drawCutSlider(g, slide);
        }
        if (settingsOpen) {
            drawSettingsPanel(g, footerTop);
        }
    }

    private void drawGlassPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int bg, int accent) {
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + 2, y + h, accent);
        g.fill(x, y, x + w, y + 1, 0x33FFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, 0x55000000);
    }

    private void drawSettingsButton(GuiGraphicsExtractor g, int mouseX, int mouseY, int slide) {
        String label = Component.translatable("lodgen.map.settings").getString();
        settingsButtonW = Math.min(width - HUD_MARGIN * 2, Math.min(156, Math.max(104, minecraft.font.width(label) + 28)));
        settingsButtonH = HUD_BUTTON_HEIGHT;
        settingsButtonX = width - settingsButtonW - HUD_MARGIN;
        settingsButtonY = HUD_MARGIN - slide;
        boolean hovered = isInsideSettingsButton(mouseX, mouseY);
        int bg = settingsOpen ? 0xD014283D : hovered ? 0xCC112338 : 0x99030914;
        int accent = settingsOpen || hovered ? 0xFF7DEBFF : 0xFF3BA4FF;
        drawGlassPanel(g, settingsButtonX, settingsButtonY, settingsButtonW, settingsButtonH, bg, accent);
        int gearX = settingsButtonX + 13;
        int gearY = settingsButtonY + 12;
        g.fill(gearX - 4, gearY - 1, gearX + 4, gearY + 1, accent);
        g.fill(gearX - 1, gearY - 4, gearX + 1, gearY + 4, accent);
        g.text(minecraft.font, label, settingsButtonX + 26, settingsButtonY + 8, 0xFFE9F2FF);
    }

    private void drawSettingsPanel(GuiGraphicsExtractor g, int footerTop) {
        MapSettings settings = MapSettings.get();
        settingsPanelW = Math.min(360, width - HUD_MARGIN * 2);
        settingsPanelH = 142;
        settingsPanelX = width - settingsPanelW - HUD_MARGIN;
        settingsPanelY = Math.max(HUD_MARGIN, footerTop - settingsPanelH - HUD_GAP);
        drawGlassPanel(g, settingsPanelX, settingsPanelY, settingsPanelW, settingsPanelH, 0xDD030914, 0xFF3BA4FF);

        int x = settingsPanelX + 12;
        int y = settingsPanelY + 10;
        pauseRowY = y + 20;
        speedRowY = y + 44;
        timeRowY = y + 68;
        lodsRowY = y + 92;
        g.text(minecraft.font, Component.translatable("lodgen.map.settings.title"), x, y, 0xFFFFFFFF);
        drawSettingValue(g, x, pauseRowY, "lodgen.map.settings.pause", onOff(settings.pauseSingleplayer), false);
        drawSettingValue(g, x, speedRowY, "lodgen.map.settings.speed", String.format("%.2fx", settings.cameraSpeed), true);
        drawSettingValue(g, x, timeRowY, "lodgen.map.settings.time", settings.timeOfDay.label().getString(), false);
        drawSettingValue(g, x, lodsRowY, "lodgen.map.settings.onlyLods", onOff(settings.onlyLods), false);
    }

    private void drawSettingValue(GuiGraphicsExtractor g, int x, int y, String labelKey, String value, boolean plusMinus) {
        int rowX = settingsPanelX + 8;
        int rowW = settingsPanelW - 16;
        g.fill(rowX, y, rowX + rowW, y + SETTINGS_ROW_HEIGHT - 2, 0x66102034);
        g.fill(rowX, y, rowX + 2, y + SETTINGS_ROW_HEIGHT - 2, 0xFF3BA4FF);
        g.text(minecraft.font, Component.translatable(labelKey), x, y + 7, 0xFFDFF8FF);
        int valueW = Math.max(62, minecraft.font.width(value) + 14);
        int valueX = settingsPanelX + settingsPanelW - valueW - 14;
        if (plusMinus) {
            valueX -= SPEED_BUTTON_SIZE + 6;
        }
        g.fill(valueX, y + 3, valueX + valueW, y + 19, 0xAA102034);
        g.fill(valueX, y + 3, valueX + 2, y + 19, 0xFF3BA4FF);
        g.text(minecraft.font, value, valueX + 7, y + 7, 0xFFFFFFFF);
        if (plusMinus) {
            speedMinusX = valueX - SPEED_BUTTON_SIZE - 6;
            speedMinusY = y + 2;
            speedPlusX = valueX + valueW + 6;
            speedPlusY = y + 2;
            drawSmallButton(g, speedMinusX, speedMinusY, "-");
            drawSmallButton(g, speedPlusX, speedPlusY, "+");
        }
    }

    private void drawSmallButton(GuiGraphicsExtractor g, int x, int y, String label) {
        g.fill(x, y, x + SPEED_BUTTON_SIZE, y + SPEED_BUTTON_SIZE, 0xCC102034);
        g.fill(x, y, x + 2, y + SPEED_BUTTON_SIZE, 0xFF7DEBFF);
        g.centeredText(minecraft.font, label, x + SPEED_BUTTON_SIZE / 2, y + 5, 0xFFFFFFFF);
    }

    private int drawFooter(GuiGraphicsExtractor g, int slide) {
        String[][] hints = keyHints();
        int contentWidth = 0;
        for (String[] hint : hints) {
            contentWidth += hintWidth(hint) + HUD_GAP;
        }
        contentWidth -= HUD_GAP;
        float scale = Math.min(1.0f, Math.max(1, width - HUD_MARGIN * 2) / (float) contentWidth);
        int visualHeight = Math.max(1, Math.round(KEY_HINT_HEIGHT * scale));
        int footerY = height - HUD_MARGIN - visualHeight + slide;

        g.pose().pushMatrix();
        g.pose().translate(HUD_MARGIN, footerY);
        g.pose().scale(scale, scale);
        int currentX = 0;
        for (String[] hint : hints) {
            int w = hintWidth(hint);
            drawGlassPanel(g, currentX, 0, w, KEY_HINT_HEIGHT, 0x99030914, 0xFF3BA4FF);
            g.text(minecraft.font, hint[0], currentX + 9, 7, 0xFFFFFFFF);
            g.text(minecraft.font, hint[1], currentX + 14 + minecraft.font.width(hint[0]), 7, 0xFFBFD8FF);
            currentX += w + HUD_GAP;
        }
        g.pose().popMatrix();
        return footerY;
    }

    private String[][] keyHints() {
        String[] keys = isNether() ? new String[] {"drag", "rotate", "move", "fast", "zoom", "cut", "center", "close"}
                : new String[] {"drag", "rotate", "move", "fast", "zoom", "center", "close"};
        String[][] hints = new String[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            String button = keys[i].equals("close")
                    ? openKey.getTranslatedKeyMessage().getString()
                    : Component.translatable("lodgen.map.key." + keys[i] + ".button").getString();
            hints[i] = new String[]{button, Component.translatable("lodgen.map.key." + keys[i]).getString()};
        }
        return hints;
    }

    private int hintWidth(String[] hint) {
        return minecraft.font.width(hint[0]) + minecraft.font.width(hint[1]) + 22;
    }

    private String onOff(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off").getString();
    }

    private boolean isInsideSettingsButton(double mouseX, double mouseY) {
        return settingsButtonW > 0 && mouseX >= settingsButtonX && mouseX <= settingsButtonX + settingsButtonW
                && mouseY >= settingsButtonY && mouseY <= settingsButtonY + settingsButtonH;
    }

    private boolean isInsideSettingsPanel(double mouseX, double mouseY) {
        return settingsOpen && settingsPanelW > 0 && mouseX >= settingsPanelX && mouseX <= settingsPanelX + settingsPanelW
                && mouseY >= settingsPanelY && mouseY <= settingsPanelY + settingsPanelH;
    }

    private boolean handleSettingsClick(double mouseX, double mouseY) {
        if (isInsideSettingsButton(mouseX, mouseY)) {
            settingsOpen = !settingsOpen;
            return true;
        }
        if (!isInsideSettingsPanel(mouseX, mouseY)) {
            if (settingsOpen) {
                settingsOpen = false;
                return true;
            }
            return false;
        }
        MapSettings settings = MapSettings.get();
        if (mouseX >= speedMinusX && mouseX <= speedMinusX + SPEED_BUTTON_SIZE && mouseY >= speedMinusY && mouseY <= speedMinusY + SPEED_BUTTON_SIZE) {
            settings.changeCameraSpeed(-0.25);
        } else if (mouseX >= speedPlusX && mouseX <= speedPlusX + SPEED_BUTTON_SIZE && mouseY >= speedPlusY && mouseY <= speedPlusY + SPEED_BUTTON_SIZE) {
            settings.changeCameraSpeed(0.25);
        } else if (isInsideSettingsRow(mouseX, mouseY, pauseRowY)) {
            settings.togglePause();
        } else if (isInsideSettingsRow(mouseX, mouseY, speedRowY)) {
            settings.changeCameraSpeed(mouseX < settingsPanelX + settingsPanelW / 2.0 ? -0.25 : 0.25);
        } else if (isInsideSettingsRow(mouseX, mouseY, timeRowY)) {
            settings.cycleTimeOfDay();
        } else if (isInsideSettingsRow(mouseX, mouseY, lodsRowY)) {
            settings.toggleOnlyLods();
            if (settings.onlyLods()) {
                VoxyRange.ingestLoadedChunks(minecraft);
            }
        }
        return true;
    }

    private boolean isInsideSettingsRow(double mouseX, double mouseY, int rowY) {
        return rowY >= 0 && mouseX >= settingsPanelX + 8 && mouseX <= settingsPanelX + settingsPanelW - 8
                && mouseY >= rowY && mouseY <= rowY + SETTINGS_ROW_HEIGHT - 2;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0 && isInsideCutSlider(mouseX, mouseY)) {
            setCut(netherCut + (int) Math.signum(scrollY));
        } else if (scrollY != 0.0) {
            zoomAt(mouseX, mouseY, Math.pow(0.82, scrollY));
        }
        return true;
    }

    private void zoomAt(double mouseX, double mouseY, double factor) {
        double newBlocksPerPixel = Mth.clamp(blocksPerPixel * factor, 0.125, 256.0);
        if (newBlocksPerPixel == blocksPerPixel) {
            return;
        }
        syncWorldCamera();
        GroundPoint anchor = mapPointAt(mouseX, mouseY);
        blocksPerPixel = newBlocksPerPixel;
        syncWorldCamera();
        keepMapPointUnderMouse(anchor, mouseX, mouseY);
        syncWorldCamera();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1 && handleSettingsClick(event.x(), event.y())) {
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1 && isInsideCutSlider(event.x(), event.y())) {
            cutDragging = true;
            dragCut(event.x());
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1) {
            dragging = true;
            middleDragging = false;
            dragLastMouseX = event.x();
            dragLastMouseY = event.y();
            moveVelocityX = 0.0;
            moveVelocityZ = 0.0;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            middleDragging = true;
            dragging = false;
            dragStartMouseX = event.x();
            dragStartYaw = viewYaw;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_1) {
            dragging = false;
            cutDragging = false;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            middleDragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (cutDragging) {
            dragCut(event.x());
            return true;
        }
        if (dragging) {
            panByPixels(event.x() - dragLastMouseX, event.y() - dragLastMouseY);
            dragLastMouseX = event.x();
            dragLastMouseY = event.y();
            syncWorldCamera();
            return true;
        }
        if (middleDragging) {
            viewYaw = dragStartYaw + (event.x() - dragStartMouseX) * 0.01;
            syncWorldCamera();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || openKey.matches(event)) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_D
                || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            // Held keys move the camera, see updateSmoothControls
            return true;
        }
        if ((key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) && isNether()) {
            setCut(netherCut + (key == GLFW.GLFW_KEY_PAGE_UP ? 4 : -4));
            return true;
        }
        if (key == GLFW.GLFW_KEY_C && minecraft.player != null) {
            viewCenterX = minecraft.player.getX();
            viewCenterY = MAP_VIEW_LEVEL;
            viewCenterZ = minecraft.player.getZ();
            syncWorldCamera();
            return true;
        }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            zoomAt(zoomMouseX(), zoomMouseY(), 0.8);
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoomAt(zoomMouseX(), zoomMouseY(), 1.25);
            return true;
        }
        return super.keyPressed(event);
    }

    private double zoomMouseX() {
        return Double.isNaN(lastMouseX) ? width / 2.0 : lastMouseX;
    }

    private double zoomMouseY() {
        return Double.isNaN(lastMouseY) ? height / 2.0 : lastMouseY;
    }

    @Override
    public void removed() {
        MapCamera.deactivate();
        // Before Voxy is made again for the shader pack, so the new one gets its own render distance
        VoxyRange.restore();
        ShaderMap.close();
        if (sentFocusX != Integer.MIN_VALUE && ClientPlayNetworking.canSend(MapFocusPayload.TYPE)) {
            ClientPlayNetworking.send(new MapFocusPayload(0, 0, 0));
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return MapSettings.get().pauseSingleplayer && minecraft != null && minecraft.getSingleplayerServer() != null
                && !minecraft.getSingleplayerServer().isPublished();
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    private record GroundPoint(double x, double z) {
    }
}
