package io.github.octomiku01.lodgen.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla has no public way to add an entry to the F3 screen */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {
    @Invoker("register")
    static Identifier lodgen$register(Identifier id, DebugScreenEntry entry) {
        throw new AssertionError();
    }
}
