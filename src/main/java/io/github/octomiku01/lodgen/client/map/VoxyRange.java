package io.github.octomiku01.lodgen.client.map;

import io.github.octomiku01.lodgen.LodGen;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Voxy keeps LODs only within its render distance around the camera, and fades them out toward its edge. Zoomed out,
 * the camera of the 3D map is far up and far to the side of where it looks, so the map would show a small disc in
 * haze. While the map is open, the render distance grows to what the map shows (never shrinks while it is open), and
 * goes back when it closes; Voxy's config file is not touched. Voxy has no interface for this: if a Voxy update
 * changes what this uses, the map keeps Voxy's own render distance.
 * <p>
 * Render thread only.
 */
final class VoxyRange {
    /** Voxy counts its render distance in sections of 32 chunks */
    private static final double SECTION_BLOCKS = 32 * 16;
    /** At most this many sections, so a far zoom does not fill the graphics card's memory */
    private static final float MAX_SECTIONS = 32;

    /** Voxy's own render distance while the map has a wider one, or -1 */
    private static float original = -1;
    private static boolean broken;
    private static boolean ingestBroken;

    private VoxyRange() {
    }

    /** The map shows this far from its camera, horizontally */
    static void cover(double blocks) {
        if (broken) {
            return;
        }
        try {
            VoxyRenderSystem voxy = IVoxyRenderSystemHolder.getNullable();
            if (voxy == null) {
                return;
            }
            float current = VoxyConfig.CONFIG.sectionRenderDistance;
            float wanted = Math.min(MAX_SECTIONS, (float) Math.ceil(blocks / SECTION_BLOCKS));
            if (wanted <= current) {
                return;
            }
            if (original < 0) {
                original = current;
            }
            // Voxy reads it from its config each frame, for its culling and fade
            VoxyConfig.CONFIG.sectionRenderDistance = wanted;
            voxy.setRenderDistance(wanted);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            LodGen.LOGGER.warn("Could not widen Voxy's render distance for the 3D map", e);
            restore();
        }
    }

    /**
     * Hands Voxy the chunks the game has loaded. It takes them in when they load, but only if their light is there
     * already, which it often is not yet: those only come when they unload. With only LODs on the map they are needed.
     */
    static void ingestLoadedChunks(Minecraft minecraft) {
        if (ingestBroken || minecraft.level == null || minecraft.player == null) {
            return;
        }
        try {
            int distance = minecraft.options.getEffectiveRenderDistance() + 1;
            int centerX = minecraft.player.chunkPosition().x();
            int centerZ = minecraft.player.chunkPosition().z();
            for (int x = centerX - distance; x <= centerX + distance; x++) {
                for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                    LevelChunk chunk = minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk != null) {
                        VoxelIngestService.tryAutoIngestChunk(chunk);
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            ingestBroken = true;
            LodGen.LOGGER.warn("Could not hand the loaded chunks to Voxy for the 3D map", e);
        }
    }

    static void restore() {
        if (original < 0) {
            return;
        }
        try {
            VoxyConfig.CONFIG.sectionRenderDistance = original;
            VoxyRenderSystem voxy = IVoxyRenderSystemHolder.getNullable();
            if (voxy != null) {
                voxy.setRenderDistance(original);
            }
        } catch (RuntimeException | LinkageError e) {
            LodGen.LOGGER.warn("Could not give Voxy its render distance back", e);
        }
        original = -1;
    }
}
