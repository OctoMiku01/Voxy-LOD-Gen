package io.github.octomiku01.lodgen.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.octomiku01.lodgen.LodGen;
import io.github.octomiku01.lodgen.MapProgressPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** The last progress view the server sent, as a texture for the 3D map; render thread only */
public final class MapProgress {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "map_progress");
    /** By state: nothing yet, horizon, terrain only, with details, real chunks */
    static final int[] COLORS = {0x55000000, 0xFFB07CFF, 0xFFF9DC5C, 0xFF5FD068, 0xFF7DEBFF};
    /** Older than this it is not shown: the server stopped sending, or the view was closed long ago */
    private static final long MAX_AGE_MILLIS = 10_000;

    private static @Nullable MapProgressPayload last;
    private static long receivedAt;
    private static @Nullable DynamicTexture texture;
    private static int textureSize;
    /** The square of cells that has anything in it, with a margin: what the view shows, so it is not mostly empty */
    private static int cropU;
    private static int cropV;
    private static int cropSize;

    private MapProgress() {
    }

    public static void receive(MapProgressPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (texture == null || textureSize != payload.size()) {
            if (texture != null) {
                minecraft.getTextureManager().release(TEXTURE);
            }
            texture = new DynamicTexture(() -> "LOD Gen progress", payload.size(), payload.size(), false);
            textureSize = payload.size();
            minecraft.getTextureManager().register(TEXTURE, texture);
        }
        NativeImage pixels = texture.getPixels();
        int size = payload.size();
        byte[] cells = payload.cells();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                int state = cells[row * size + column];
                pixels.setPixel(column, row, COLORS[state >= 0 && state < COLORS.length ? state : 0]);
            }
        }
        texture.upload();
        crop(cells, size);
        last = payload;
        receivedAt = System.currentTimeMillis();
    }

    private static void crop(byte[] cells, int size) {
        int minColumn = size;
        int maxColumn = -1;
        int minRow = size;
        int maxRow = -1;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                if (cells[row * size + column] != 0) {
                    minColumn = Math.min(minColumn, column);
                    maxColumn = Math.max(maxColumn, column);
                    minRow = Math.min(minRow, row);
                    maxRow = Math.max(maxRow, row);
                }
            }
        }
        if (maxColumn < 0) {
            cropU = 0;
            cropV = 0;
            cropSize = size;
            return;
        }
        int side = Math.max(maxColumn - minColumn, maxRow - minRow) + 1;
        side = Math.min(size, Math.max(Math.min(size, 16), side + Math.max(2, side / 12) * 2));
        int centerColumn = (minColumn + maxColumn + 1) / 2;
        int centerRow = (minRow + maxRow + 1) / 2;
        cropU = Math.clamp(centerColumn - side / 2, 0, size - side);
        cropV = Math.clamp(centerRow - side / 2, 0, size - side);
        cropSize = side;
    }

    static int cropU() {
        return cropU;
    }

    static int cropV() {
        return cropV;
    }

    static int cropSize() {
        return cropSize;
    }

    /** The view of the player's dimension, if a recent one came */
    static @Nullable MapProgressPayload current(Minecraft minecraft) {
        if (last == null || minecraft.level == null || !last.dimension().equals(minecraft.level.dimension())
                || System.currentTimeMillis() - receivedAt > MAX_AGE_MILLIS) {
            return null;
        }
        return last;
    }

    public static void clear() {
        last = null;
    }
}
