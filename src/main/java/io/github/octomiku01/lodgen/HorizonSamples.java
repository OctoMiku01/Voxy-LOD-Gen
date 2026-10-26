package io.github.octomiku01.lodgen;

import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;

/**
 * What a rough LOD of the horizon is made of (see {@link LodHorizonBuilder}): the height of the ground and its biome at
 * the corners of a grid over one chunk, corner (i, j) at index i * corners + j. Small enough to store for the whole
 * world and to send to players, who build the LOD themselves.
 *
 * @param step    blocks between the grid corners: 4, 8 or 16
 * @param heights the first block above the solid ground, below water if there is some
 */
public record HorizonSamples(int step, int[] heights, Holder<Biome>[] biomes) {
    public int corners() {
        return corners(this.step);
    }

    static int corners(int step) {
        return 16 / step + 1;
    }

    /** Whether these are at least as fine as a grid with this step: the coarser grid's corners are among them */
    boolean covers(int step) {
        return this.step <= step;
    }

    void write(RegistryFriendlyByteBuf buf) {
        IdMap<Holder<Biome>> ids = buf.registryAccess().lookupOrThrow(Registries.BIOME).asHolderIdMap();
        buf.writeByte(this.step);
        for (int i = 0; i < this.heights.length; i++) {
            // Signed: the ground can lie below 0
            buf.writeVarInt(this.heights[i]);
            buf.writeVarInt(ids.getId(this.biomes[i]));
        }
    }

    static HorizonSamples read(RegistryFriendlyByteBuf buf) {
        IdMap<Holder<Biome>> ids = buf.registryAccess().lookupOrThrow(Registries.BIOME).asHolderIdMap();
        int step = buf.readByte();
        if (step != 4 && step != 8 && step != 16) {
            throw new IllegalArgumentException("Grid step " + step);
        }
        int count = corners(step) * corners(step);
        int[] heights = new int[count];
        Holder<Biome>[] biomes = newBiomes(count);
        for (int i = 0; i < count; i++) {
            heights[i] = buf.readVarInt();
            biomes[i] = ids.byIdOrThrow(buf.readVarInt());
        }
        return new HorizonSamples(step, heights, biomes);
    }

    @SuppressWarnings("unchecked")
    static Holder<Biome>[] newBiomes(int count) {
        return new Holder[count];
    }
}
