package io.github.octomiku01.lodgen.client;

import io.github.octomiku01.lodgen.LodGen;
import io.github.octomiku01.lodgen.mixin.DebugScreenEntriesInvoker;
import io.github.octomiku01.lodgen.mixin.DebugScreenEntryListAccessor;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * A line on the F3 screen: how the generation of the world this game hosts goes, or how many LODs came from the
 * server. It can be switched like every entry (F3 + F6); until someone does, it shows with the F3 screen.
 */
final class LodDebugEntry implements DebugScreenEntry {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(LodGen.MOD_ID, "status");

    private LodDebugEntry() {
    }

    static void register() {
        DebugScreenEntriesInvoker.lodgen$register(ID, new LodDebugEntry());
    }

    /** Once the options are loaded: on in the F3 screen unless a status was chosen and saved for it */
    static void showByDefault(Minecraft client) {
        Map<Identifier, DebugScreenEntryStatus> statuses = ((DebugScreenEntryListAccessor) client.debugEntries).lodgen$allStatuses();
        if (!statuses.containsKey(ID)) {
            // Not setStatus: that would also switch away from the chosen profile and save
            statuses.put(ID, DebugScreenEntryStatus.IN_OVERLAY);
            client.debugEntries.rebuildCurrentList();
        }
    }

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        String hosted = LodGen.debugLine();
        if (hosted != null) {
            displayer.addLine(hosted);
        } else if (FabricLoader.getInstance().isModLoaded("voxy") && Minecraft.getInstance().getConnection() != null) {
            int pending = ClientLodReceiver.pending();
            int horizon = ClientHorizonReceiver.built();
            int horizonWaiting = ClientHorizonReceiver.waiting();
            displayer.addLine("LOD Gen: " + ClientLodReceiver.received() + " LODs received" + (pending > 0 ? ", " + pending + " waiting for Voxy" : "")
                    + (horizon > 0 || horizonWaiting > 0 ? ", horizon " + horizon + (horizonWaiting > 0 ? " (" + horizonWaiting + " to build)" : "") : ""));
        }
    }
}
