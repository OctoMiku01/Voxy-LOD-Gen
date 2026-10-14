package io.github.octomiku01.lodgen;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/** Hands sections to Voxy. Only loaded when Voxy is installed. */
final class VoxySink implements LodSink {
    private final WorldIdentifier world;

    private VoxySink(WorldIdentifier world) {
        this.world = world;
    }

    static @Nullable LodSink create(ServerLevel level) {
        WorldIdentifier world = WorldIdentifier.of(level);
        return world == null ? null : new VoxySink(world);
    }

    @Override
    public boolean accept(int chunkX, int sectionY, int chunkZ, LevelChunkSection section, @Nullable DataLayer skyLight) {
        return VoxelIngestService.rawIngest(this.world, section, chunkX, sectionY, chunkZ, null, skyLight);
    }
}
