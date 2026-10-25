package io.github.octomiku01.lodgen.mixin;

import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Tells apart an F3 entry nobody chose a status for from one switched off on purpose (both read as NEVER) */
@Mixin(DebugScreenEntryList.class)
public interface DebugScreenEntryListAccessor {
    @Accessor("allStatuses")
    Map<Identifier, DebugScreenEntryStatus> lodgen$allStatuses();
}
