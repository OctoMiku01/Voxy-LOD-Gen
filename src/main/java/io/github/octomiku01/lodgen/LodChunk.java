package io.github.octomiku01.lodgen;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.jetbrains.annotations.Nullable;

/**
 * The generated terrain of one chunk, ready for Voxy: its sections from the bottom of the world up to just above the
 * terrain, each with approximated sky light. Built once and shared by everyone who needs it, so it must not change.
 */
public final class LodChunk {
    /** Sky light of a section that lies completely above the terrain. Shared, never modified. */
    static final DataLayer FULL_SKY_LIGHT = new DataLayer(15);

    private static final int LIGHT_NONE = 0;
    private static final int LIGHT_FULL = 1;
    private static final int LIGHT_DATA = 2;

    public interface SectionConsumer {
        void accept(int sectionY, LevelChunkSection section, @Nullable DataLayer skyLight);
    }

    final int x;
    final int z;
    private final int minSectionY;
    private final LevelChunkSection[] sections;
    private final @Nullable DataLayer[] skyLight;
    private byte @Nullable [] encoded;

    LodChunk(int x, int z, int minSectionY, LevelChunkSection[] sections, @Nullable DataLayer[] skyLight) {
        this.x = x;
        this.z = z;
        this.minSectionY = minSectionY;
        this.sections = sections;
        this.skyLight = skyLight;
    }

    void forEachSection(SectionConsumer consumer) {
        for (int i = 0; i < this.sections.length; i++) {
            consumer.accept(this.minSectionY + i, this.sections[i], this.skyLight[i]);
        }
    }

    /** The network form, in the vanilla section format. Serialized on first use and then reused for every player. */
    synchronized byte[] encoded() {
        if (this.encoded == null) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeVarInt(this.minSectionY);
            buf.writeVarInt(this.sections.length);
            for (int i = 0; i < this.sections.length; i++) {
                this.sections[i].write(buf);
                DataLayer light = this.skyLight[i];
                if (light == null) {
                    buf.writeByte(LIGHT_NONE);
                } else if (light == FULL_SKY_LIGHT) {
                    buf.writeByte(LIGHT_FULL);
                } else {
                    buf.writeByte(LIGHT_DATA);
                    buf.writeBytes(light.getData());
                }
            }
            this.encoded = new byte[buf.readableBytes()];
            buf.readBytes(this.encoded);
            buf.release();
        }
        return this.encoded;
    }

    /** Reads {@link #encoded()} back, on the receiving client. */
    public static void decode(byte[] data, PalettedContainerFactory containers, SectionConsumer consumer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        int minSectionY = buf.readVarInt();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            LevelChunkSection section = new LevelChunkSection(containers);
            section.read(buf);
            DataLayer light = switch (buf.readByte()) {
                case LIGHT_FULL -> FULL_SKY_LIGHT;
                case LIGHT_DATA -> {
                    byte[] bytes = new byte[DataLayer.SIZE];
                    buf.readBytes(bytes);
                    yield new DataLayer(bytes);
                }
                default -> null;
            };
            consumer.accept(minSectionY + i, section, light);
        }
    }
}
