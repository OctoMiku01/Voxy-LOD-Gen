package io.github.octomiku01.lodgen.client.map;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.octomiku01.lodgen.LodGen;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * The 3D map while a shader pack is on: shader packs draw their own clouds and haze, which nothing outside the pack
 * can turn off. So Voxy is drawn alone over the finished frame, through its own pipeline instead of the pack's
 * (as 3D Maps Voxy Addon does). Voxy has no interface for this: the mixins in {@code mixin.voxy} reach into it, and
 * if a Voxy update moved what they hook into, the map turns the shader pack off while it is open instead.
 * <p>
 * Render thread only.
 */
public final class ShaderMap {
    /** Frames to wait for the hook in Voxy's rendering to show up before giving up on it */
    private static final int HOOK_FRAMES = 20;
    private static final Vector4f BACKGROUND = new Vector4f(0.03f, 0.03f, 0.06f, 1.0f);

    private enum Mode { NONE, VOXY_ALONE, SHADERS_OFF }

    private static Mode mode = Mode.NONE;
    /** Drawing Voxy alone failed once: from then on the shader pack is turned off instead */
    private static boolean voxyAloneBroken;
    /** Set by the mixins whenever Voxy runs through them, so it is known that they took hold */
    public static volatile boolean pipelineHookSeen;
    public static volatile boolean renderHookSeen;
    private static boolean drawing;
    private static int framesWithoutHook;
    private static int framebuffer;
    private static int framebufferColor;
    private static int framebufferDepth;

    private ShaderMap() {
    }

    /** Voxy is asked for its pipeline: with the map open over a shader pack, its own one instead of the pack's */
    public static boolean forceVoxyPipeline() {
        return mode == Mode.VOXY_ALONE;
    }

    /** Voxy wants to draw into the world: with the map open over a shader pack it draws later, alone */
    public static boolean holdBackVoxy() {
        return mode == Mode.VOXY_ALONE && !drawing;
    }

    static void open(Minecraft minecraft) {
        if (mode != Mode.NONE || !Iris.shaderPackInUse()) {
            return;
        }
        if (!voxyAloneBroken) {
            try {
                pipelineHookSeen = false;
                renderHookSeen = false;
                framesWithoutHook = 0;
                mode = Mode.VOXY_ALONE;
                rebuildVoxy();
                if (!pipelineHookSeen && IVoxyRenderSystemHolder.getNullable() != null) {
                    throw new IllegalStateException("the hook into Voxy's pipeline did not take hold");
                }
                return;
            } catch (RuntimeException | LinkageError e) {
                giveUpVoxyAlone(e);
            }
        }
        if (Iris.setShaders(false)) {
            mode = Mode.SHADERS_OFF;
        }
    }

    static void close() {
        Mode was = mode;
        mode = Mode.NONE;
        if (was == Mode.VOXY_ALONE) {
            releaseFramebuffer();
            try {
                rebuildVoxy();
            } catch (RuntimeException | LinkageError e) {
                LodGen.LOGGER.error("Could not give Voxy its shader pipeline back", e);
            }
        } else if (was == Mode.SHADERS_OFF) {
            Iris.setShaders(true);
        }
    }

    /** Before the GUI: the map over a shader pack is Voxy alone */
    public static void beforeGui(Minecraft minecraft) {
        if (mode != Mode.VOXY_ALONE) {
            return;
        }
        try {
            if (!renderHookSeen && ++framesWithoutHook > HOOK_FRAMES && IVoxyRenderSystemHolder.getNullable() != null) {
                throw new IllegalStateException("the hook into Voxy's rendering did not take hold");
            }
            draw(minecraft);
        } catch (RuntimeException | LinkageError e) {
            giveUpVoxyAlone(e);
            mode = Mode.NONE;
            releaseFramebuffer();
            try {
                rebuildVoxy();
            } catch (RuntimeException | LinkageError again) {
                LodGen.LOGGER.error("Could not give Voxy its shader pipeline back", again);
            }
            if (Iris.setShaders(false)) {
                mode = Mode.SHADERS_OFF;
            }
        }
    }

    private static void draw(Minecraft minecraft) {
        VoxyRenderSystem voxy = IVoxyRenderSystemHolder.getNullable();
        RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        if (!(target.getColorTexture() instanceof GlTexture color) || !(target.getDepthTexture() instanceof GlTexture depth)) {
            return;
        }
        // The pack's frame is replaced: its clouds and haze would hide the map
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, BACKGROUND, depth, 0.0);
        if (voxy == null || voxy.getViewport() == null || !bindFramebuffer(color.glId(), depth.glId())) {
            return;
        }
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        drawing = true;
        try {
            voxy.renderOpaque(voxy.getViewport(), depth.glId(), color.glId());
        } finally {
            drawing = false;
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }

    private static boolean bindFramebuffer(int color, int depth) {
        if (framebuffer != 0 && framebufferColor == color && framebufferDepth == depth) {
            return true;
        }
        releaseFramebuffer();
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
        boolean complete = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) == GL30C.GL_FRAMEBUFFER_COMPLETE;
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        if (!complete) {
            releaseFramebuffer();
            return false;
        }
        framebufferColor = color;
        framebufferDepth = depth;
        return true;
    }

    private static void releaseFramebuffer() {
        if (framebuffer != 0) {
            GL30C.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        framebufferColor = 0;
        framebufferDepth = 0;
    }

    /** Voxy picks its pipeline only when its renderer is made */
    private static void rebuildVoxy() {
        IVoxyRenderSystemHolder holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder != null && Minecraft.getInstance().level != null) {
            holder.voxy$shutdownRenderer();
            holder.voxy$createRenderer();
        }
    }

    private static void giveUpVoxyAlone(Throwable e) {
        voxyAloneBroken = true;
        LodGen.LOGGER.warn("Could not draw Voxy alone for the 3D map, the shader pack is turned off while it is open instead", e);
    }

    /** Iris through its public API, by reflection: it is not needed to build or run this mod */
    private static final class Iris {
        private static final String API = "net.irisshaders.iris.api.v0.IrisApi";

        static boolean shaderPackInUse() {
            try {
                Object api = api();
                return api != null && (boolean) Class.forName(API).getMethod("isShaderPackInUse").invoke(api);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return false;
            }
        }

        static boolean setShaders(boolean enabled) {
            try {
                Object api = api();
                if (api == null) {
                    return false;
                }
                Object config = Class.forName(API).getMethod("getConfig").invoke(api);
                Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("setShadersEnabledAndApply", boolean.class)
                        .invoke(config, enabled);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LodGen.LOGGER.warn("Could not turn the shader pack {} for the 3D map", enabled ? "on" : "off", e);
                return false;
            }
        }

        private static Object api() throws ReflectiveOperationException {
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
                return null;
            }
            return Class.forName(API).getMethod("getInstance").invoke(null);
        }
    }
}
