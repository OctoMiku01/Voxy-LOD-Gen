package io.github.octomiku01.lodgen.client.map;

import io.github.octomiku01.lodgen.LodGen;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Waypoints in Xaero's Minimap, made from the 3D map: its own "add waypoint" screen opens, filled in with the spot,
 * as Xaero's World Map does it. Through reflection: Xaero's Minimap is not needed to build or run this mod, and if
 * an update of it moved what this uses, the map simply offers no waypoints.
 */
final class XaeroWaypoints {
    /** What Xaero's screen takes for a height it does not know: the field then shows "~" */
    private static final int UNKNOWN_Y = 32767;

    private static boolean broken;

    private XaeroWaypoints() {
    }

    static boolean available() {
        FabricLoader loader = FabricLoader.getInstance();
        return !broken && (loader.isModLoaded("xaerominimap") || loader.isModLoaded("xaerominimapfair"));
    }

    /** Xaero's screen to add a waypoint at the spot, coming back to {@code parent}; null if it cannot be made */
    static @Nullable Screen addScreen(Minecraft minecraft, Screen parent, int x, @Nullable Integer y, int z) {
        if (!available() || minecraft.level == null) {
            return null;
        }
        try {
            Object hudMod = Class.forName("xaero.common.HudMod").getField("INSTANCE").get(null);
            Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
            Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
            if (hudMod == null || session == null) {
                return null;
            }
            Object worldManager = session.getClass().getMethod("getWorldManager").invoke(session);
            Object world = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (world == null) {
                return null;
            }
            Object container = world.getClass().getMethod("getContainer").invoke(world);
            Object root = container.getClass().getMethod("getRoot").invoke(container);
            Object rootPath = root.getClass().getMethod("getPath").invoke(root);
            Object set = world.getClass().getMethod("getCurrentWaypointSetId").invoke(world);
            double coordinateScale = minecraft.level.dimensionType().coordinateScale();
            return (Screen) addWaypointConstructor().newInstance(hudMod, session, parent, parent, new ArrayList<>(), rootPath, world, set,
                    true, true, x, y == null ? UNKNOWN_Y : y, z, coordinateScale, world);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            LodGen.LOGGER.warn("Could not open Xaero's Minimap to add a waypoint from the 3D map", e);
            return null;
        }
    }

    /**
     * GuiAddWaypoint(HudMod, MinimapSession, Screen parent, Screen escape, ArrayList edited, XaeroPath container,
     * MinimapWorld world, String set, boolean adding, boolean hasForcedPlayerPos, int x, int y, int z,
     * double coordinateScale, MinimapWorld coordinateSourceWorld)
     */
    private static Constructor<?> addWaypointConstructor() throws ReflectiveOperationException {
        for (Constructor<?> constructor : Class.forName("xaero.common.gui.GuiAddWaypoint").getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 15 && types[10] == int.class && types[13] == double.class) {
                return constructor;
            }
        }
        throw new NoSuchMethodException("GuiAddWaypoint with a given position");
    }
}
