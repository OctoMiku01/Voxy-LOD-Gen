package io.github.octomiku01.lodgen.client;

import io.github.octomiku01.lodgen.LodGen;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL45C;

/**
 * Hides far players behind LOD terrain while a shader pack is on. Without one, Voxy writes the LODs' depth into the
 * game's depth buffer and they hide what is behind them by themselves. Most shader packs keep it in Voxy's own depth
 * buffer instead and lay the LODs in behind everything the game drew, so far players would show through mountains.
 * <p>
 * Right after Voxy has drawn, the depth and stencil there are read back at a few points of each far player: the
 * stencil tells whether the pixel shows LODs or the game's own terrain (whose depth works with shaders too). The read
 * goes into a buffer the GPU fills on its own and is picked up a frame later, so neither side waits for the other.
 * If Voxy changes what this reads, players simply stay visible. Render thread only.
 */
public final class LodOcclusion {
    private static final int MAX_SAMPLES = 64;
    /** Reads in flight at once; a frame's reads are usually done by the next one */
    private static final int BUFFERS = 3;
    /** Hidden once this many results in a row say so: a single pixel at the edge of a hill would flicker */
    private static final int HIDE_AFTER = 2;
    private static final String IRIS_PIPELINE = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline";

    private record Sample(UUID id, Vec3 point) {
    }

    private record Pending(int buffer, long fence, UUID[] ids, float[] distance, Matrix4f inverseProjection, float[] ndcX, float[] ndcY,
                           boolean zeroToOne, int count) {
    }

    private static final List<Sample> REQUESTED = new ArrayList<>();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<Integer> FREE_BUFFERS = new ArrayDeque<>();
    private static final Map<UUID, Integer> OCCLUDED_RUNS = new HashMap<>();
    private static boolean active;
    private static boolean broken;
    private static @Nullable Field pipelineField;

    private LodOcclusion() {
    }

    /** Whether points should be handed in this frame: only while it is needed, and only as long as it works */
    static boolean active() {
        return active && !broken;
    }

    /** Points of a far player to test, e.g. its head and the middle of its body; it shows as long as one is visible */
    static void request(UUID id, Vec3... points) {
        for (Vec3 point : points) {
            if (REQUESTED.size() < MAX_SAMPLES) {
                REQUESTED.add(new Sample(id, point));
            }
        }
    }

    public static boolean hidden(UUID id) {
        return active() && OCCLUDED_RUNS.getOrDefault(id, 0) >= HIDE_AFTER;
    }

    /** Called right after Voxy drew its LODs for the frame */
    public static void afterVoxy(VoxyRenderSystem system) {
        if (broken) {
            return;
        }
        try {
            // None during a shader pack's shadow pass, which Voxy draws too: only the frame itself counts, and the
            // points handed in for it must wait for it
            Viewport<?> viewport = system.getViewport();
            if (viewport == null) {
                return;
            }
            collect();
            AbstractRenderPipeline pipeline = pipelineOf(system);
            active = needed(pipeline);
            if (active && !REQUESTED.isEmpty()) {
                read(pipeline, viewport);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            active = false;
            OCCLUDED_RUNS.clear();
            LodGen.LOGGER.warn("Could not read Voxy's depth, far players stay visible through LOD terrain with shader packs", e);
        }
        REQUESTED.clear();
    }

    private static AbstractRenderPipeline pipelineOf(VoxyRenderSystem system) throws ReflectiveOperationException {
        if (pipelineField == null) {
            pipelineField = VoxyRenderSystem.class.getDeclaredField("pipeline");
            pipelineField.setAccessible(true);
        }
        return (AbstractRenderPipeline) pipelineField.get(system);
    }

    /** Only with a shader pack that keeps the LODs' depth to itself */
    private static boolean needed(AbstractRenderPipeline pipeline) {
        if (!pipeline.getClass().getName().equals(IRIS_PIPELINE)) {
            OCCLUDED_RUNS.clear();
            return false;
        }
        return !IrisPipeline.rendersToVanillaDepth(pipeline);
    }

    private static void read(AbstractRenderPipeline pipeline, Viewport<?> viewport) {
        GlTexture depth = pipeline.fb.getDepthTex();
        if (depth == null || depth.getFormat() != GL30C.GL_DEPTH24_STENCIL8) {
            throw new IllegalStateException("Voxy's depth buffer is not 24 bit depth with stencil");
        }
        Integer buffer = FREE_BUFFERS.poll();
        if (buffer == null) {
            if (PENDING.size() >= BUFFERS) {
                return;
            }
            buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferData(buffer, MAX_SAMPLES * 4L, GL15C.GL_STREAM_READ);
        }
        int width = depth.getWidth();
        int height = depth.getHeight();
        Matrix4f projection = new Matrix4f(viewport.projection);
        Matrix4f view = new Matrix4f(viewport.modelView);
        int count = REQUESTED.size();
        UUID[] ids = new UUID[count];
        float[] distance = new float[count];
        float[] ndcX = new float[count];
        float[] ndcY = new float[count];

        int previousBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int rowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
        int skipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffer);
        GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
        int used = 0;
        try {
            for (Sample sample : REQUESTED) {
                Vector4f point = new Vector4f((float) (sample.point.x - viewport.cameraX), (float) (sample.point.y - viewport.cameraY),
                        (float) (sample.point.z - viewport.cameraZ), 1.0f);
                view.transform(point);
                float ahead = -point.z;
                projection.transform(point);
                if (ahead <= 0 || point.w <= 0) {
                    continue;
                }
                float x = point.x / point.w;
                float y = point.y / point.w;
                if (x < -1 || x > 1 || y < -1 || y > 1) {
                    continue;
                }
                int pixelX = Math.min(width - 1, (int) ((x + 1) * 0.5f * width));
                int pixelY = Math.min(height - 1, (int) ((y + 1) * 0.5f * height));
                GL45C.glGetTextureSubImage(depth.id, 0, pixelX, pixelY, 0, 1, 1, 1, GL30C.GL_DEPTH_STENCIL, GL30C.GL_UNSIGNED_INT_24_8,
                        4, used * 4L);
                ids[used] = sample.id;
                distance[used] = ahead;
                ndcX[used] = x;
                ndcY[used] = y;
                used++;
            }
        } finally {
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, skipRows);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, skipPixels);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousBuffer);
        }
        if (used == 0) {
            FREE_BUFFERS.add(buffer);
            return;
        }
        long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        PENDING.add(new Pending(buffer, fence, ids, distance, projection.invert(), ndcX, ndcY, viewport.properties.isZero2One(), used));
    }

    /** Takes in the reads the GPU has finished */
    private static void collect() {
        while (!PENDING.isEmpty()) {
            Pending pending = PENDING.peek();
            int state = GL32C.glClientWaitSync(pending.fence, 0, 0);
            if (state != GL32C.GL_ALREADY_SIGNALED && state != GL32C.GL_CONDITION_SATISFIED) {
                return;
            }
            PENDING.poll();
            GL32C.glDeleteSync(pending.fence);
            ByteBuffer data = ByteBuffer.allocateDirect(pending.count * 4).order(ByteOrder.nativeOrder());
            GL45C.glGetNamedBufferSubData(pending.buffer, 0, data);
            FREE_BUFFERS.add(pending.buffer);
            evaluate(pending, data);
        }
    }

    private static void evaluate(Pending pending, ByteBuffer data) {
        Set<UUID> seen = new HashSet<>();
        Set<UUID> visible = new HashSet<>();
        Vector4f point = new Vector4f();
        for (int i = 0; i < pending.count; i++) {
            UUID id = pending.ids[i];
            seen.add(id);
            int value = data.getInt(i * 4);
            // Stencil 0: the game's own terrain is there, its depth hides the player by itself
            if ((value & 0xFF) == 0) {
                visible.add(id);
                continue;
            }
            float depth = (value >>> 8) / (float) 0xFFFFFF;
            float ndcZ = pending.zeroToOne ? depth : depth * 2 - 1;
            pending.inverseProjection.transform(point.set(pending.ndcX[i], pending.ndcY[i], ndcZ, 1.0f));
            float lodDistance = point.w == 0 ? Float.POSITIVE_INFINITY : -point.z / point.w;
            // A little room: the LODs are coarse, a player standing on them must not count as behind them
            float margin = 2.0f + pending.distance[i] * 0.01f;
            if (!(lodDistance > 0 && lodDistance + margin < pending.distance[i])) {
                visible.add(id);
            }
        }
        for (UUID id : seen) {
            if (visible.contains(id)) {
                OCCLUDED_RUNS.remove(id);
            } else {
                OCCLUDED_RUNS.merge(id, 1, Integer::sum);
            }
        }
        OCCLUDED_RUNS.keySet().retainAll(seen);
    }

    /** Only loaded with Iris there: Voxy's pipeline data refers to it */
    private static final class IrisPipeline {
        static boolean rendersToVanillaDepth(AbstractRenderPipeline pipeline) {
            return ((me.cortex.voxy.client.core.IrisVoxyRenderPipeline) pipeline)._getData().renderToVanillaDepth;
        }
    }
}
