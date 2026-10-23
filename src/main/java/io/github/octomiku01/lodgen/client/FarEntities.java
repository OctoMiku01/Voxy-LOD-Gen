package io.github.octomiku01.lodgen.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.octomiku01.lodgen.FarEntitiesPayload;
import io.github.octomiku01.lodgen.LodGen;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the players beyond the view distance that the server sends (see {@code FarPlayers}), with what they ride.
 * Each one is a stand-in entity that is not part of the level: players are moved and animated like vanilla moves
 * other players, vehicles are only moved. Once the real player is near enough for the game to show it, the stand-in
 * is no longer drawn. Used on the client thread only.
 */
public final class FarEntities {
    /** Like vanilla's interpolation of other players */
    private static final int LERP_STEPS = 3;
    private static final double VANILLA_NAME_TAG_DISTANCE = 64;
    /** Stand-ins get ids far away from those the server hands out */
    private static final int FIRST_ID = -1_000_000;

    private static final class Far {
        final Entity entity;
        @Nullable UUID vehicle;
        /** Block light in the upper, sky light in the lower four bits, as the server saw them there */
        byte light;
        // Vehicles are not ticked; they move towards where the server last saw them
        Vec3 target;
        float targetYRot, targetXRot;
        int steps;

        Far(Entity entity, Vec3 target) {
            this.entity = entity;
            this.target = target;
        }
    }

    private static final Map<UUID, Far> ENTITIES = new HashMap<>();
    /** Types that threw while being updated or drawn, e.g. a modded vehicle that needs more than a client copy has */
    private static final Set<EntityType<?>> BROKEN = new HashSet<>();
    private static @Nullable ClientLevel level;
    private static int nextId = FIRST_ID;

    private FarEntities() {
    }

    static void receive(FarEntitiesPayload payload, Minecraft client) {
        ClientLevel current = client.level;
        if (current != level) {
            clear();
            level = current;
        }
        if (current == null || !current.dimension().equals(payload.dimension())) {
            return;
        }
        for (UUID uuid : payload.removed()) {
            Far far = ENTITIES.remove(uuid);
            if (far != null) {
                remove(far);
            }
        }
        for (FarEntitiesPayload.Entry entry : payload.entries()) {
            if (BROKEN.contains(entry.type())) {
                continue;
            }
            try {
                update(entry, current, client.getConnection());
            } catch (Throwable t) {
                broken(entry.type(), ENTITIES.remove(entry.uuid()), t);
            }
        }
        // Riders get on and off once everything is up to date
        for (Far far : ENTITIES.values()) {
            Far vehicle = far.vehicle == null ? null : ENTITIES.get(far.vehicle);
            Entity entity = far.entity;
            if (vehicle == null) {
                if (entity.isPassenger()) {
                    entity.stopRiding();
                }
            } else if (entity.getVehicle() != vehicle.entity) {
                entity.stopRiding();
                // Forced: the stand-ins are not in the level, vanilla's checks for getting on would fail
                entity.startRiding(vehicle.entity, true, false);
            }
        }
    }

    private static void update(FarEntitiesPayload.Entry entry, ClientLevel level, @Nullable ClientPacketListener connection) {
        Vec3 position = new Vec3(entry.x(), entry.y(), entry.z());
        float yRot = Mth.unpackDegrees(entry.yRot());
        float xRot = Mth.unpackDegrees(entry.xRot());
        float headYRot = Mth.unpackDegrees(entry.headYRot());
        Far far = ENTITIES.get(entry.uuid());
        if (far != null && far.entity.getType() != entry.type()) {
            remove(far);
            far = null;
        }
        if (far == null) {
            Entity entity = create(entry, level, connection);
            if (entity == null) {
                return;
            }
            entity.setId(nextId--);
            entity.snapTo(position, yRot, xRot);
            entity.setOldPosAndRot();
            entity.setYHeadRot(headYRot);
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = yRot;
                living.yBodyRotO = yRot;
                living.yHeadRotO = headYRot;
            }
            far = new Far(entity, position);
            far.targetYRot = yRot;
            far.targetXRot = xRot;
            ENTITIES.put(entry.uuid(), far);
        } else if (far.entity instanceof RemotePlayer player) {
            player.moveOrInterpolateTo(position, yRot, xRot);
            player.lerpHeadTo(headYRot, LERP_STEPS);
        } else {
            far.target = position;
            far.targetYRot = yRot;
            far.targetXRot = xRot;
            far.steps = LERP_STEPS;
        }
        far.vehicle = entry.vehicle();
        far.light = entry.light();
        Entity entity = far.entity;
        if (entry.data() != null) {
            entity.getEntityData().assignValues(FarEntitiesPayload.decodeData(entry.data(), level.registryAccess()));
        }
        if (entry.equipment() != null && entity instanceof LivingEntity living) {
            FarEntitiesPayload.decodeEquipment(entry.equipment(), level.registryAccess(), living::setItemSlot);
        }
    }

    private static @Nullable Entity create(FarEntitiesPayload.Entry entry, ClientLevel level, @Nullable ClientPacketListener connection) {
        if (entry.type() == EntityTypes.PLAYER) {
            // From the player list, which also has their skin
            PlayerInfo info = connection == null ? null : connection.getPlayerInfo(entry.uuid());
            GameProfile profile = info != null ? info.getProfile() : new GameProfile(entry.uuid(), "");
            return new RemotePlayer(level, profile);
        }
        Entity entity = entry.type().create(level, EntitySpawnReason.LOAD);
        if (entity != null) {
            entity.setUUID(entry.uuid());
            entity.noPhysics = true;
        }
        return entity;
    }

    static void tick(Minecraft client) {
        if (client.level != level) {
            clear();
            level = client.level;
            return;
        }
        if (ENTITIES.isEmpty() || client.isPaused()) {
            return;
        }
        // Vehicles first, their riders are placed on them
        for (Far far : List.copyOf(ENTITIES.values())) {
            if (!(far.entity instanceof RemotePlayer)) {
                tick(far, false);
            }
        }
        for (Far far : List.copyOf(ENTITIES.values())) {
            if (far.entity instanceof RemotePlayer) {
                tick(far, true);
            }
        }
    }

    private static void tick(Far far, boolean player) {
        Entity entity = far.entity;
        try {
            // As ClientLevel ticks entities and their passengers
            entity.setOldPosAndRot();
            entity.tickCount++;
            if (player) {
                if (entity.isPassenger()) {
                    entity.rideTick();
                } else {
                    entity.tick();
                }
            } else {
                moveVehicle(far);
            }
        } catch (Throwable t) {
            ENTITIES.values().remove(far);
            broken(entity.getType(), far, t);
        }
    }

    private static void moveVehicle(Far far) {
        Entity entity = far.entity;
        Vec3 from = entity.position();
        if (far.steps > 0) {
            double part = 1.0 / far.steps;
            entity.setPos(from.lerp(far.target, part));
            entity.setYRot(Mth.rotLerp((float) part, entity.getYRot(), far.targetYRot));
            entity.setXRot(Mth.lerp((float) part, entity.getXRot(), far.targetXRot));
            far.steps--;
        }
        if (entity instanceof LivingEntity living) {
            // Living vehicles face where they go and move their legs, which ticking would do
            living.yBodyRot = entity.getYRot();
            living.yBodyRotO = entity.yRotO;
            living.setYHeadRot(entity.getYRot());
            living.yHeadRotO = entity.yRotO;
            float moved = (float) Mth.length(entity.getX() - from.x, 0, entity.getZ() - from.z);
            living.walkAnimation.update(Math.min(moved * 4.0F, 1.0F), 0.4F, living.isBaby() ? 3.0F : 1.0F);
        }
    }

    static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel current = client.level;
        if (current == null || current != level) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        CameraRenderState camera = context.levelState().cameraRenderState;
        Frustum frustum = camera.initialized ? camera.cullFrustum : null;
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Hidden with F1, like all name tags
        boolean names = !client.gui.hud.isHidden();
        List<NameTag> nameTags = new ArrayList<>();
        List<Far> failed = new ArrayList<>();
        for (Far far : ENTITIES.values()) {
            Entity entity = far.entity;
            if (isShownByGame(entity, current)) {
                continue;
            }
            if (LodOcclusion.active()) {
                requestOcclusion(entity);
                if (LodOcclusion.hidden(entity.getUUID())) {
                    continue;
                }
            }
            // Their chunks are not loaded here, so the light comes from the server; burning ones glow, as in vanilla
            int light = LightCoordsUtil.pack(entity.isOnFire() ? 15 : far.light >> 4 & 15, far.light & 15);
            if (names && entity instanceof RemotePlayer player && !player.getGameProfile().name().isEmpty()) {
                addNameTag(nameTags, player, client, frustum, partialTick, light);
            }
            if (frustum != null && !frustum.isVisible(entity.getBoundingBox().inflate(0.5))) {
                continue;
            }
            PoseStack.Pose mark = poseStack.last();
            try {
                EntityRenderState state = dispatcher.extractEntity(entity, partialTick);
                state.lightCoords = light;
                // The name tag is drawn below, also further away than vanilla draws it
                state.nameTag = null;
                state.scoreText = null;
                dispatcher.submit(state, camera, state.x - camera.pos.x, state.y - camera.pos.y, state.z - camera.pos.z,
                        poseStack, context.submitNodeCollector());
            } catch (Throwable t) {
                while (poseStack.last() != mark && !poseStack.isEmpty()) {
                    poseStack.popPose();
                }
                failed.add(far);
                LodGen.LOGGER.error("Could not draw a far {}", entity.getType(), t);
            }
        }
        for (Far far : failed) {
            ENTITIES.values().remove(far);
            broken(far.entity.getType(), far, null);
        }
        if (LodOcclusion.active()) {
            // Real players too: at the edge of the loaded chunks they can stand under terrain that only LODs show
            for (Player player : current.players()) {
                if (player != client.player && player != client.getCameraEntity()) {
                    requestOcclusion(player);
                }
            }
        }
        if (names) {
            // Real players beyond vanilla's name tag distance that the game still draws
            for (Player player : current.players()) {
                if (player != client.player && player != client.getCameraEntity()
                        && player.distanceToSqr(camera.pos) >= VANILLA_NAME_TAG_DISTANCE * VANILLA_NAME_TAG_DISTANCE
                        && frustum != null && dispatcher.shouldRender(player, frustum, camera.pos.x, camera.pos.y, camera.pos.z)) {
                    addNameTag(nameTags, player, client, frustum, partialTick, dispatcher.getPackedLightCoords(player, partialTick));
                }
            }
            submitNameTags(nameTags, context, camera, client);
        }
    }

    /** Behind LOD terrain once neither the middle of the body nor the head shows */
    private static void requestOcclusion(Entity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 middle = box.getCenter();
        LodOcclusion.request(entity.getUUID(), middle, new Vec3(middle.x, box.maxY - 0.2, middle.z));
    }

    private record NameTag(Component name, Vec3 position, int light) {
    }

    /** Follows vanilla's rules, except for the distance: not while sneaking or invisible, and as the teams allow */
    private static void addNameTag(List<NameTag> nameTags, Player player, Minecraft client, @Nullable Frustum frustum, float partialTick, int light) {
        if (player.isDiscrete() || client.player == null || !isNameVisible(player, client.player)) {
            return;
        }
        Vec3 attachment = player.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, player.getYRot(partialTick));
        if (attachment == null) {
            return;
        }
        Vec3 position = player.getPosition(partialTick).add(attachment.x, attachment.y + 0.5, attachment.z);
        if (frustum == null || frustum.isVisible(AABB.ofSize(position, 1, 1, 1))) {
            nameTags.add(new NameTag(player.getDisplayName(), position, light));
        }
    }

    private static boolean isNameVisible(Player player, Player viewer) {
        boolean visible = !player.isInvisibleTo(viewer);
        Team team = player.getTeam();
        Team own = viewer.getTeam();
        if (team == null) {
            return visible;
        }
        return switch (team.getNameTagVisibility()) {
            case ALWAYS -> visible;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> own == null ? visible : team.isAlliedTo(own) && (team.canSeeFriendlyInvisibles() || visible);
            case HIDE_FOR_OWN_TEAM -> own == null ? visible : !team.isAlliedTo(own) && visible;
        };
    }

    /**
     * Like vanilla's name tags, but hidden behind terrain (vanilla shows them through walls, which would give away
     * where far players are) and growing with the distance so they stay readable. The plate and the text are drawn
     * apart: so far away they would be too close together in the depth buffer and flicker.
     */
    private static void submitNameTags(List<NameTag> nameTags, LevelRenderContext context, CameraRenderState camera, Minecraft client) {
        PoseStack poseStack = context.poseStack();
        int background = (int) (client.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        // Zooming in (spyglass, Zoomify...) narrows the view and makes everything larger: the tags then size themselves
        // as if the player were that much closer, so they keep the size they would have there without zooming
        double zoom = Math.max(1.0, Math.tan(Math.toRadians(client.options.fov().get()) / 2.0) * camera.projectionMatrix.m11());
        for (Font.DisplayMode mode : new Font.DisplayMode[]{Font.DisplayMode.NORMAL, Font.DisplayMode.POLYGON_OFFSET}) {
            boolean plate = mode == Font.DisplayMode.NORMAL;
            for (NameTag nameTag : nameTags) {
                // Vanilla's size up close, then they shrink only slowly: about 8 pixels high at 64 blocks on a 1080p
                // screen, still 5 at 2000 (vanilla's would be 2 at 64 blocks)
                double distance = nameTag.position.distanceTo(camera.pos) / zoom;
                float scale = 0.025F * (float) Math.pow(Math.max(1.0, distance / 16.0), 0.85);
                poseStack.pushPose();
                poseStack.translate(nameTag.position.subtract(camera.pos));
                poseStack.mulPose(camera.orientation);
                poseStack.scale(scale, -scale, scale);
                context.submitNodeCollector().submitText(poseStack, -client.font.width(nameTag.name) / 2.0F, 0, nameTag.name.getVisualOrderText(),
                        false, mode, nameTag.light, -1, plate ? background : 0, 0);
                poseStack.popPose();
            }
        }
    }

    /** The real one is within the view distance; for a vehicle, also when the real rider is (it brings the vehicle) */
    private static boolean isShownByGame(Entity entity, ClientLevel level) {
        if (level.getEntity(entity.getUUID()) != null) {
            return true;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (level.getEntity(passenger.getUUID()) != null) {
                return true;
            }
        }
        return false;
    }

    private static void broken(EntityType<?> type, @Nullable Far far, @Nullable Throwable t) {
        if (BROKEN.add(type) && t != null) {
            LodGen.LOGGER.error("Far {} are not shown anymore in this session", type, t);
        }
        if (far != null) {
            remove(far);
        }
    }

    private static void remove(Far far) {
        try {
            far.entity.ejectPassengers();
            far.entity.stopRiding();
        } catch (Throwable ignored) {
            // A broken modded vehicle; the stand-in is dropped anyway
        }
    }

    /** The players beyond the view distance shown on the LODs, e.g. for the 3D map */
    public static List<RemotePlayer> players() {
        List<RemotePlayer> players = new ArrayList<>();
        for (Far far : ENTITIES.values()) {
            if (far.entity instanceof RemotePlayer player && (level == null || !isShownByGame(player, level))) {
                players.add(player);
            }
        }
        return players;
    }

    static void clear() {
        for (Far far : ENTITIES.values()) {
            remove(far);
        }
        ENTITIES.clear();
    }

    /** A new session: types that failed may work on another server */
    static void reset() {
        clear();
        BROKEN.clear();
        level = null;
    }
}
