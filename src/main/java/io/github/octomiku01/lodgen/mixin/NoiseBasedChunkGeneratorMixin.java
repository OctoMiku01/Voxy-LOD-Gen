package io.github.octomiku01.lodgen.mixin;

import io.github.octomiku01.lodgen.StructureHeights;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Terrain heights asked again while finding structure starts for LODs come from {@link StructureHeights}. Not required:
 * if another mod replaces the method, structures are only found slower.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void lodgen$rememberedHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState, CallbackInfoReturnable<Integer> info) {
        int height = StructureHeights.get(this, x, z, type, heightAccessor, randomState);
        if (height != StructureHeights.UNKNOWN) {
            info.setReturnValue(height);
        }
    }

    @Inject(method = "getBaseHeight", at = @At("RETURN"), require = 0)
    private void lodgen$rememberHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState, CallbackInfoReturnable<Integer> info) {
        StructureHeights.put(this, x, z, type, heightAccessor, randomState, info.getReturnValue());
    }
}
