package io.github.octomiku01.lodgen.client.map;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.jetbrains.annotations.Nullable;

/**
 * What a right click on the 3D map offers for the spot under the mouse, under its coordinates and biome: a waypoint there (Xaero's Minimap), teleporting
 * there (for players allowed to), copying its coordinates or sharing them in the chat. Drawn like the map's other
 * panels, next to where it was opened.
 */
final class MapContextMenu {
    private static final int ROW_HEIGHT = 18;
    private static final int PADDING = 6;
    private static final int TITLE_HEIGHT = 16;
    private static final int BIOME_HEIGHT = 11;

    /**
     * The spot: the block to stand on; y is null where the map found no ground under the mouse, biome (e.g.
     * minecraft:plains) where it is not known
     */
    record Spot(int x, @Nullable Integer y, int z, @Nullable String biome) {
        String coordinates() {
            return this.x + " " + (this.y == null ? "~" : this.y) + " " + this.z;
        }
    }

    private record Option(Component label, Runnable action) {
    }

    private final Spot spot;
    private final List<Option> options = new ArrayList<>();
    private final Component title;
    private final @Nullable Component biome;
    private int x;
    private int y;
    private int w;
    private int h;

    /** @param waypoint opens Xaero's screen to add a waypoint at the spot; null without Xaero's Minimap */
    MapContextMenu(Minecraft minecraft, Spot spot, @Nullable Runnable waypoint) {
        this.spot = spot;
        this.title = Component.literal(spot.coordinates());
        this.biome = spot.biome == null ? null : biomeName(spot.biome);
        if (waypoint != null) {
            this.options.add(new Option(Component.translatable("lodgen.map.menu.waypoint"), waypoint));
        }
        // Like Xaero's World Map: the command does it, so only for players who may use it, and only onto known ground
        if (spot.y != null && minecraft.player != null && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            this.options.add(new Option(Component.translatable("lodgen.map.menu.teleport"), () -> {
                minecraft.player.connection.sendCommand("tp @s " + spot.x + " " + spot.y + " " + spot.z);
                minecraft.gui.setScreen(null);
            }));
        }
        this.options.add(new Option(Component.translatable("lodgen.map.menu.copy"),
                () -> minecraft.keyboardHandler.setClipboard(spot.coordinates())));
        if (minecraft.player != null) {
            this.options.add(new Option(Component.translatable("lodgen.map.menu.share"), () -> {
                minecraft.player.connection.sendChat("[" + spot.x + ", " + (spot.y == null ? "~" : spot.y) + ", " + spot.z + "]");
                minecraft.gui.setScreen(null);
            }));
        }
    }

    /** Translated like the game names biomes (biome.minecraft.plains), also those of mods; else the id */
    private static Component biomeName(String id) {
        int colon = id.indexOf(':');
        return Component.translatableWithFallback("biome." + id.substring(0, Math.max(colon, 0)) + "." + id.substring(colon + 1), id);
    }

    private int titleHeight() {
        return this.biome == null ? TITLE_HEIGHT : TITLE_HEIGHT + BIOME_HEIGHT;
    }

    Spot spot() {
        return this.spot;
    }

    /** Lays the menu out next to the mouse, inside the screen */
    void place(Minecraft minecraft, Screen screen, double mouseX, double mouseY) {
        int textW = minecraft.font.width(this.title);
        if (this.biome != null) {
            textW = Math.max(textW, minecraft.font.width(this.biome));
        }
        for (Option option : this.options) {
            textW = Math.max(textW, minecraft.font.width(option.label));
        }
        this.w = textW + PADDING * 2 + 6;
        this.h = this.titleHeight() + this.options.size() * ROW_HEIGHT + PADDING;
        this.x = (int) Math.min(mouseX + 4, screen.width - this.w - 4);
        this.y = (int) Math.min(mouseY + 4, screen.height - this.h - 4);
    }

    void draw(Minecraft minecraft, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(this.x, this.y, this.x + this.w, this.y + this.h, 0xE6030914);
        g.fill(this.x, this.y, this.x + 2, this.y + this.h, 0xFF3BA4FF);
        g.fill(this.x, this.y, this.x + this.w, this.y + 1, 0x33FFFFFF);
        g.fill(this.x, this.y + this.h - 1, this.x + this.w, this.y + this.h, 0x55000000);
        g.text(minecraft.font, this.title, this.x + PADDING + 2, this.y + 5, 0xFF9FD8FF);
        if (this.biome != null) {
            g.text(minecraft.font, this.biome, this.x + PADDING + 2, this.y + 5 + BIOME_HEIGHT, 0xFFBFD8FF);
        }
        int hovered = this.optionAt(mouseX, mouseY);
        for (int i = 0; i < this.options.size(); i++) {
            int rowY = this.y + this.titleHeight() + i * ROW_HEIGHT;
            if (i == hovered) {
                g.fill(this.x + 2, rowY, this.x + this.w, rowY + ROW_HEIGHT, 0xCC112338);
                g.fill(this.x, rowY, this.x + 2, rowY + ROW_HEIGHT, 0xFF7DEBFF);
            }
            g.text(minecraft.font, this.options.get(i).label, this.x + PADDING + 2, rowY + 5, i == hovered ? 0xFFFFFFFF : 0xFFDFF8FF);
        }
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.w && mouseY >= this.y && mouseY < this.y + this.h;
    }

    /** Runs the option under the mouse; @return whether there was one */
    boolean click(double mouseX, double mouseY) {
        int index = this.optionAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        this.options.get(index).action.run();
        return true;
    }

    private int optionAt(double mouseX, double mouseY) {
        if (!this.contains(mouseX, mouseY) || mouseY < this.y + this.titleHeight()) {
            return -1;
        }
        int index = (int) ((mouseY - this.y - this.titleHeight()) / ROW_HEIGHT);
        return index < this.options.size() ? index : -1;
    }
}
