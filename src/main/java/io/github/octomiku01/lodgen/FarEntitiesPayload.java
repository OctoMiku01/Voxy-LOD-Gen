package io.github.octomiku01.lodgen;

import io.github.octomiku01.lodgen.mixin.SynchedEntityDataAccessor;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Server to client: players beyond the view distance and what they ride, see {@link FarPlayers}. Only the entities
 * that changed are in it; {@code removed} are no longer shown.
 */
public record FarEntitiesPayload(ResourceKey<Level> dimension, List<Entry> entries, List<UUID> removed) implements CustomPacketPayload {
    public static final Type<FarEntitiesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "far_entities"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FarEntitiesPayload> CODEC = StreamCodec.ofMember(FarEntitiesPayload::write, FarEntitiesPayload::read);
    private static final StreamCodec<RegistryFriendlyByteBuf, EntityType<?>> ENTITY_TYPE = ByteBufCodecs.registry(Registries.ENTITY_TYPE);
    private static final int END_OF_DATA = 255;

    /**
     * One entity. Angles are packed like vanilla's (256 steps).
     *
     * @param light     the block light where it is in the upper and the sky light in the lower four bits: the client
     *                  has no light data that far away
     * @param vehicle   what it rides, sent as an entry of its own before it
     * @param data      its synched entity data (pose, flags, variant...) as {@link FarPlayers} encodes it, null if
     *                  unchanged since the last time
     * @param equipment its equipment as {@link FarPlayers} encodes it, null if unchanged or it has none
     */
    public record Entry(UUID uuid, EntityType<?> type, double x, double y, double z, byte yRot, byte xRot, byte headYRot,
                        byte light, @Nullable UUID vehicle, byte @Nullable [] data, byte @Nullable [] equipment) {
    }

    /**
     * All of the entity's synched data, the way vanilla sends it. All of it, not only what differs from the defaults:
     * the client must also learn when something goes back to its default, like a player who stops sneaking.
     */
    static byte[] encodeData(Entity entity, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        for (SynchedEntityData.DataItem<?> item : ((SynchedEntityDataAccessor) entity.getEntityData()).lodgen$itemsById()) {
            item.value().write(buf);
        }
        buf.writeByte(END_OF_DATA);
        return ByteBufUtil.getBytes(buf);
    }

    public static List<SynchedEntityData.DataValue<?>> decodeData(byte[] data, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        for (int id = buf.readUnsignedByte(); id != END_OF_DATA; id = buf.readUnsignedByte()) {
            values.add(SynchedEntityData.DataValue.read(buf, id));
        }
        return values;
    }

    /** Everything the entity wears and holds: armor, hands, saddle, horse armor... */
    static byte[] encodeEquipment(LivingEntity entity, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, entity.getItemBySlot(slot));
        }
        return ByteBufUtil.getBytes(buf);
    }

    public static void decodeEquipment(byte[] data, RegistryAccess registries, BiConsumer<EquipmentSlot, ItemStack> consumer) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            consumer.accept(slot, ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeVarInt(this.entries.size());
        for (Entry entry : this.entries) {
            buf.writeUUID(entry.uuid);
            ENTITY_TYPE.encode(buf, entry.type);
            buf.writeDouble(entry.x);
            buf.writeDouble(entry.y);
            buf.writeDouble(entry.z);
            buf.writeByte(entry.yRot);
            buf.writeByte(entry.xRot);
            buf.writeByte(entry.headYRot);
            buf.writeByte(entry.light);
            buf.writeNullable(entry.vehicle, (b, uuid) -> b.writeUUID(uuid));
            buf.writeNullable(entry.data, (b, data) -> b.writeByteArray(data));
            buf.writeNullable(entry.equipment, (b, data) -> b.writeByteArray(data));
        }
        buf.writeVarInt(this.removed.size());
        for (UUID uuid : this.removed) {
            buf.writeUUID(uuid);
        }
    }

    private static FarEntitiesPayload read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readUUID(), ENTITY_TYPE.decode(buf), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(),
                    buf.readNullable(b -> b.readUUID()),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())));
        }
        int removedCount = buf.readVarInt();
        List<UUID> removed = new ArrayList<>(Math.min(removedCount, 1024));
        for (int i = 0; i < removedCount; i++) {
            removed.add(buf.readUUID());
        }
        return new FarEntitiesPayload(dimension, entries, removed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
