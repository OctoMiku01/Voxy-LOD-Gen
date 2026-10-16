package io.github.octomiku01.lodgen.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {
    /** Every value, also those set back to the default: far players get all of them, not only what changed */
    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] lodgen$itemsById();
}
