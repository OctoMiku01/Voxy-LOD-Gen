package io.github.octomiku01.lodgen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdStructure;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla lays out the pieces of a stronghold with state kept in static fields (which pieces are left, their total
 * weight). LOD workers find structures on many threads at once, and two strongholds laid out at the same time broke
 * each other ("Bound must be positive"). Only this step runs one at a time; it is rare and quick.
 */
@Mixin(StrongholdStructure.class)
public abstract class StrongholdStructureMixin {
    @WrapMethod(method = "generatePieces")
    private static void lodgen$oneAtATime(StructurePiecesBuilder builder, Structure.GenerationContext context, Operation<Void> original) {
        synchronized (StrongholdStructure.class) {
            original.call(builder, context);
        }
    }
}
