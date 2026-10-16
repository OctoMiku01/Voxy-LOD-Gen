package io.github.octomiku01.lodgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;

/**
 * Shows players beyond the view distance, with what they ride, to everyone who gets LODs: vanilla only sends the
 * players within it, so far away ones would be missing on the LOD terrain. Sends where they are and how they look
 * (pose, skin layers, armor, held items, the horse's color...) and the light there to the clients that have this mod; they draw them.
 * Only what changed is sent, every second tick like vanilla does for players.
 */
final class FarPlayers {
    private static final int INTERVAL = 2;
    /** Everything is sent again this often, in case a client missed something, e.g. while changing dimension */
    private static final int REFRESH_INTERVAL = 100;

    private final MinecraftServer server;
    private final double maxDistanceSquared;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private int ticks;

    /** A player who gets far players, and what they got */
    private static final class Viewer {
        @Nullable ResourceKey<Level> dimension;
        final Map<UUID, Sent> sent = new HashMap<>();
    }

    private static final class Sent {
        double x, y, z;
        byte yRot, xRot, headYRot, light;
        @Nullable UUID vehicle;
        byte[] data;
        byte @Nullable [] equipment;
    }

    /** One entity as it is this tick, built once for all viewers */
    private record Snapshot(UUID uuid, Entity entity, double x, double y, double z, byte yRot, byte xRot, byte headYRot,
                            byte light, @Nullable UUID vehicle, byte[] data, byte @Nullable [] equipment) {
    }

    FarPlayers(MinecraftServer server, int radiusChunks) {
        this.server = server;
        double maxDistance = radiusChunks * 16.0;
        this.maxDistanceSquared = maxDistance * maxDistance;
    }

    void tick() {
        if (++this.ticks % INTERVAL != 0) {
            return;
        }
        boolean refresh = this.ticks % REFRESH_INTERVAL == 0;
        this.viewers.keySet().removeIf(uuid -> this.server.getPlayerList().getPlayer(uuid) == null);
        Map<Entity, Snapshot> snapshots = new IdentityHashMap<>();
        for (ServerPlayer viewer : this.server.getPlayerList().getPlayers()) {
            if (!ServerPlayNetworking.canSend(viewer, FarEntitiesPayload.TYPE)) {
                continue;
            }
            Viewer state = this.viewers.computeIfAbsent(viewer.getUUID(), uuid -> new Viewer());
            ServerLevel level = viewer.level();
            if (level.dimension() != state.dimension) {
                // The client drops what it showed when it changes dimension
                state.dimension = level.dimension();
                state.sent.clear();
            }
            // Vanilla shows other players within the view distance, in a circle; up to two chunks inside its edge
            // they are sent here too, so there is no gap while one walks across it
            int viewDistance = Mth.clamp(viewer.requestedViewDistance(), 2, this.server.getPlayerList().getViewDistance());
            double nearDistance = Math.max(0, (viewDistance - 2) * 16.0);
            List<FarEntitiesPayload.Entry> entries = new ArrayList<>();
            Set<UUID> shown = new HashSet<>();
            for (ServerPlayer target : level.players()) {
                if (target == viewer || !isShown(target)) {
                    continue;
                }
                double dx = target.getX() - viewer.getX();
                double dz = target.getZ() - viewer.getZ();
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > this.maxDistanceSquared || distanceSquared < nearDistance * nearDistance) {
                    continue;
                }
                Entity vehicle = target.getVehicle();
                if (vehicle instanceof Player) {
                    vehicle = null;
                }
                // The vehicle first, so the client has it when the rider gets on; two riders share it
                if (vehicle != null && shown.add(vehicle.getUUID())) {
                    add(state, snapshots.computeIfAbsent(vehicle, entity -> snapshot(entity, null, level)), refresh, entries);
                }
                if (shown.add(target.getUUID())) {
                    Entity ridden = vehicle;
                    add(state, snapshots.computeIfAbsent(target, entity -> snapshot(entity, ridden, level)), refresh, entries);
                }
            }
            List<UUID> removed = new ArrayList<>();
            for (Iterator<UUID> iterator = state.sent.keySet().iterator(); iterator.hasNext(); ) {
                UUID uuid = iterator.next();
                if (!shown.contains(uuid)) {
                    removed.add(uuid);
                    iterator.remove();
                }
            }
            if (!entries.isEmpty() || !removed.isEmpty()) {
                ServerPlayNetworking.send(viewer, new FarEntitiesPayload(level.dimension(), entries, removed));
            }
        }
    }

    /** Clients stop showing far players, e.g. when the setting is turned off */
    void stop() {
        for (Map.Entry<UUID, Viewer> viewer : this.viewers.entrySet()) {
            ServerPlayer player = this.server.getPlayerList().getPlayer(viewer.getKey());
            Viewer state = viewer.getValue();
            if (player != null && state.dimension != null && !state.sent.isEmpty()) {
                ServerPlayNetworking.send(player, new FarEntitiesPayload(state.dimension, List.of(), List.copyOf(state.sent.keySet())));
            }
        }
        this.viewers.clear();
    }

    private static boolean isShown(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isInvisible();
    }

    private static Snapshot snapshot(Entity entity, @Nullable Entity vehicle, ServerLevel level) {
        RegistryAccess registries = level.registryAccess();
        // Where vanilla reads the light for drawing an entity
        BlockPos eye = BlockPos.containing(entity.getEyePosition());
        byte light = (byte) (level.getBrightness(LightLayer.BLOCK, eye) << 4 | level.getBrightness(LightLayer.SKY, eye));
        return new Snapshot(entity.getUUID(), entity, entity.getX(), entity.getY(), entity.getZ(),
                (byte) Mth.packDegrees(entity.getYRot()), (byte) Mth.packDegrees(entity.getXRot()), (byte) Mth.packDegrees(entity.getYHeadRot()),
                light, vehicle == null ? null : vehicle.getUUID(),
                FarEntitiesPayload.encodeData(entity, registries),
                entity instanceof LivingEntity living ? FarEntitiesPayload.encodeEquipment(living, registries) : null);
    }

    /** Adds the entity to what the viewer gets, with only the parts that changed since the last time */
    private static void add(Viewer state, Snapshot snapshot, boolean refresh, List<FarEntitiesPayload.Entry> entries) {
        Sent sent = state.sent.get(snapshot.uuid);
        boolean added = sent == null;
        if (added) {
            sent = new Sent();
            state.sent.put(snapshot.uuid, sent);
        }
        boolean moved = added || refresh || sent.x != snapshot.x || sent.y != snapshot.y || sent.z != snapshot.z
                || sent.yRot != snapshot.yRot || sent.xRot != snapshot.xRot || sent.headYRot != snapshot.headYRot
                || sent.light != snapshot.light
                || !Objects.equals(sent.vehicle, snapshot.vehicle);
        boolean dataChanged = added || refresh || !Arrays.equals(sent.data, snapshot.data);
        boolean equipmentChanged = snapshot.equipment != null && (added || refresh || !Arrays.equals(sent.equipment, snapshot.equipment));
        if (!moved && !dataChanged && !equipmentChanged) {
            return;
        }
        entries.add(new FarEntitiesPayload.Entry(snapshot.uuid, snapshot.entity.getType(), snapshot.x, snapshot.y, snapshot.z,
                snapshot.yRot, snapshot.xRot, snapshot.headYRot, snapshot.light, snapshot.vehicle,
                dataChanged ? snapshot.data : null, equipmentChanged ? snapshot.equipment : null));
        sent.x = snapshot.x;
        sent.y = snapshot.y;
        sent.z = snapshot.z;
        sent.yRot = snapshot.yRot;
        sent.xRot = snapshot.xRot;
        sent.headYRot = snapshot.headYRot;
        sent.light = snapshot.light;
        sent.vehicle = snapshot.vehicle;
        sent.data = snapshot.data;
        sent.equipment = snapshot.equipment;
    }
}
