package io.github.octomiku01.lodgen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: the horizon of several chunks, as {@link HorizonSamples} from which the client builds the rough
 * LODs itself (see {@link LodHorizonBuilder}): a few dozen bytes per chunk instead of kilobytes. Only sent to clients
 * that registered a receiver for it, i.e. that have a version of this mod with the horizon and Voxy installed.
 */
public record LodHorizonPayload(ResourceKey<Level> dimension, List<Chunk> chunks) implements CustomPacketPayload {
    public static final Type<LodHorizonPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "horizon"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LodHorizonPayload> CODEC = StreamCodec.ofMember(LodHorizonPayload::write, LodHorizonPayload::read);
    /** Chunks in one payload at most */
    static final int MAX_CHUNKS = 256;

    public record Chunk(int x, int z, HorizonSamples samples) {
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeVarInt(this.chunks.size());
        for (Chunk chunk : this.chunks) {
            buf.writeVarInt(chunk.x);
            buf.writeVarInt(chunk.z);
            chunk.samples.write(buf);
        }
    }

    private static LodHorizonPayload read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        int count = buf.readVarInt();
        if (count > MAX_CHUNKS) {
            throw new IllegalArgumentException("Too many chunks: " + count);
        }
        List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chunks.add(new Chunk(buf.readVarInt(), buf.readVarInt(), HorizonSamples.read(buf)));
        }
        return new LodHorizonPayload(dimension, chunks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
