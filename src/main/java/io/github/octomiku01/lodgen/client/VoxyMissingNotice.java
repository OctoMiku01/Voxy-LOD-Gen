package io.github.octomiku01.lodgen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Without Voxy this mod shows nothing: says so once per start, when the title screen first shows */
final class VoxyMissingNotice {
    private static final String VOXY_PAGE = "https://modrinth.com/mod/voxy";

    private static boolean shown;

    private VoxyMissingNotice() {
    }

    static void tick(Minecraft client) {
        // Not while the game still loads: the loading overlay lies over a title screen that is set again after it
        if (shown || !client.isGameLoadFinished() || client.gui == null || client.gui.overlay() != null || !(client.gui.screen() instanceof TitleScreen title)) {
            return;
        }
        shown = true;
        client.gui.setScreen(new ConfirmScreen(open -> {
            if (open) {
                Util.getPlatform().openUri(VOXY_PAGE);
            }
            client.gui.setScreen(title);
        }, Component.translatable("lodgen.voxyMissing.title"), Component.translatable("lodgen.voxyMissing.message"),
                Component.translatable("lodgen.voxyMissing.open"), Component.translatable("lodgen.voxyMissing.ignore")));
    }
}
