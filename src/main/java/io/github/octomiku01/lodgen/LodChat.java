package io.github.octomiku01.lodgen;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** How the /lodgen commands answer in chat: a tag in front, labels in gray, values in white, states in colour */
final class LodChat {
    private static final int BAR_WIDTH = 20;

    private LodChat() {
    }

    /** "[LOD Gen] " */
    static MutableComponent tag() {
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("LOD Gen").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
    }

    static Component info(String text) {
        return tag().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    static Component success(String text) {
        return tag().append(Component.literal(text).withStyle(ChatFormatting.GREEN));
    }

    static Component warning(String text) {
        return tag().append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    static Component error(String text) {
        return tag().append(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    static MutableComponent label(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    static MutableComponent value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    static MutableComponent value(long number) {
        return value(String.format("%,d", number));
    }

    /** " · " between values on one line */
    static MutableComponent separator() {
        return Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY);
    }

    static MutableComponent newLine() {
        return Component.literal("\n");
    }

    /**
     * A ring's progress: a bar, "ring/limit" and the share, or "starting" / "done"
     *
     * @param ring the ring reached so far, negative before the first one
     */
    static MutableComponent progress(int ring, int limit) {
        if (ring < 0) {
            return Component.literal("starting").withStyle(ChatFormatting.YELLOW);
        }
        if (ring > limit) {
            return Component.literal("|".repeat(BAR_WIDTH)).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(" done").withStyle(ChatFormatting.GREEN));
        }
        double share = limit <= 0 ? 1.0 : ring / (double) limit;
        int filled = (int) Math.round(share * BAR_WIDTH);
        return Component.literal("|".repeat(filled)).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("|".repeat(BAR_WIDTH - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(value(" " + ring + "/" + limit))
                .append(label(String.format(" (%d%%)", Math.round(share * 100))));
    }
}
