package io.github.chatglot.fabric.config.entry;

import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class CodexAuthButtonEntry extends AbstractConfigListEntry<Void> {
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ENTRY_HEIGHT = 24;

    private final Button button;

    public CodexAuthButtonEntry(Component buttonText, Runnable onPress) {
        super(Component.empty(), false);
        this.button = Button.builder(buttonText, pressed -> onPress.run())
            .bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build();
    }

    @Override
    public int getItemHeight() {
        return ENTRY_HEIGHT;
    }

    @Override
    public boolean isMouseInside(int entryX, int entryY, int entryWidth, int entryHeight, int mouseX, int mouseY) {
        return button.isMouseOver(mouseX, mouseY);
    }

    @Override
    public void extractRenderState(
        GuiGraphicsExtractor context,
        int index,
        int y,
        int x,
        int entryWidth,
        int entryHeight,
        int mouseX,
        int mouseY,
        boolean hovered,
        float delta
    ) {
        int buttonX = x + Math.max(0, entryWidth - BUTTON_WIDTH);
        button.setPosition(buttonX, y + 2);
        button.setWidth(BUTTON_WIDTH);
        button.active = isEditable();
        button.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean onlyHovering) {
        return this.button.mouseClicked(click, onlyHovering);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return this.button.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return this.button.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of(button);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of(button);
    }

    @Override
    public Void getValue() {
        return null;
    }

    @Override
    public Optional<Void> getDefaultValue() {
        return Optional.empty();
    }
}
