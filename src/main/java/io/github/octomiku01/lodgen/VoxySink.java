package io.github.octomiku01.lodgen;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Hands LODs straight to Voxy in this game instance: for the player hosting a single player world, whose client runs
 * in the same process as the server. Only loaded when Voxy is installed.
 */
public final class VoxySink implements LodSink {
    private final WorldIdentifier world;

    private VoxySink(WorldIdentifier world) {
        this.world = world;
    }

    /** Sections Voxy was given but has not taken in yet; they wait in memory */
    public static int backlog() {
        VoxyInstance instance = VoxyCommon.getInstance();
        return instance == null ? 0 : instance.getIngestService().getTaskCount();
    }

    static @Nullable LodSink create(ServerLevel level) {
        WorldIdentifier world = WorldIdentifier.of(level);
        return world == null ? null : new VoxySink(world);
    }

    @Override
    public boolean accept(LodChunk chunk) {
        boolean[] accepted = {true};
        chunk.forEachSection((sectionY, section, skyLight, blockLight) -> {
            if (accepted[0] && !VoxelIngestService.rawIngest(this.world, section, chunk.x, sectionY, chunk.z, blockLight, skyLight)) {
                accepted[0] = false;
            }
        });
        return accepted[0];
    }
}
