package io.github.octomiku01.lodgen;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/** Receives the generated sections of one dimension. */
interface LodSink {
    /**
     * @return false if the data was not accepted and the chunk should be generated again later
     */
    boolean accept(int chunkX, int sectionY, int chunkZ, LevelChunkSection section, @Nullable DataLayer skyLight);

    /** For measuring generation speed without Voxy (benchmark mode) */
    LodSink DISCARD = (chunkX, sectionY, chunkZ, section, skyLight) -> true;
}
