package io.github.octomiku01.lodgen.client;

import io.github.octomiku01.lodgen.UpdateCheck;
import java.net.URI;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * Tells the player in chat about a newer version, once per start, a few seconds after a world is joined: by then the
 * messages that come with joining are through, so this one is the newest
 */
final class UpdateNotice {
    private static final long DELAY_NANOS = 3_000_000_000L;

    private static boolean shown;
    /** When the message is due, 0 while not in a world */
    private static long dueNanos;

    private UpdateNotice() {
    }

    static void joined() {
        dueNanos = Math.max(1, System.nanoTime() + DELAY_NANOS);
    }

    static void left() {
        dueNanos = 0;
    }

    static void tick(Minecraft client) {
        UpdateCheck.Update update = UpdateCheck.update();
        if (shown || dueNanos == 0 || System.nanoTime() - dueNanos < 0 || update == null || client.level == null) {
            return;
        }
        shown = true;
        String installed = FabricLoader.getInstance().getModContainer("lodgen")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString().split("\\+", 2)[0]).orElse("?");
        Component link = Component.translatable("lodgen.update.open").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(update.page()))));
        Component message = Component.translatable(update.downloaded() ? "lodgen.update.downloaded" : "lodgen.update.available",
                update.version(), installed).append(" ").append(link);
        client.gui.hud.getChat().addClientSystemMessage(message);
    }
}
