package io.github.octomiku01.lodgen.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Structure templates cache their blocks by type in a plain HashMap, but LOD workers place the same structures on
 * several threads at once. Vanilla has the same race between its worldgen threads; C2ME fixes it the same way.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodgen$threadSafeCache(CallbackInfo info) {
        this.cache = new ConcurrentHashMap<>();
    }
}
