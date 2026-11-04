package io.github.octomiku01.lodgen.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Asks for a waypoint's name, over the 3D map, and goes back to it: Enter or Done takes the name, Escape keeps none */
final class WaypointNameScreen extends Screen {
    private static final int MAX_LENGTH = 48;

    private final Screen map;
    private final String initial;
    private final Consumer<String> onDone;
    private EditBox field;

    WaypointNameScreen(Screen map, Component title, String initial, Consumer<String> onDone) {
        super(title);
        this.map = map;
        this.initial = initial;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        int fieldW = Math.min(240, this.width - 40);
        this.field = new EditBox(this.font, (this.width - fieldW) / 2, this.height / 2 - 20, fieldW, 20, this.title);
        this.field.setMaxLength(MAX_LENGTH);
        this.field.setValue(this.initial);
        this.field.moveCursorToEnd(false);
        this.field.setHighlightPos(0);
        this.addRenderableWidget(this.field);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.done())
                .bounds(this.width / 2 - 102, this.height / 2 + 8, 100, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                .bounds(this.width / 2 + 2, this.height / 2 + 8, 100, 20).build());
        this.setInitialFocus(this.field);
    }

    private void done() {
        String name = this.field.getValue().strip();
        if (!name.isEmpty()) {
            this.onDone.accept(name);
        }
        this.onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            this.done();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 36, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.map);
    }

    @Override
    public boolean isPauseScreen() {
        return this.map.isPauseScreen();
    }
}
