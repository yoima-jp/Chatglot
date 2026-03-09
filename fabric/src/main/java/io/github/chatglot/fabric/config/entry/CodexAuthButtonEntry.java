package io.github.chatglot.fabric.config.entry;

import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class CodexAuthButtonEntry extends AbstractConfigListEntry<Void> {
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ENTRY_HEIGHT = 24;

    private final ButtonWidget button;

    public CodexAuthButtonEntry(Text buttonText, Runnable onPress) {
        super(Text.empty(), false);
        this.button = ButtonWidget.builder(buttonText, pressed -> onPress.run())
            .dimensions(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
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
    public void render(
        DrawContext context,
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
        button.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean onlyHovering) {
        return this.button.mouseClicked(click, onlyHovering);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return this.button.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return this.button.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public List<? extends Element> children() {
        return List.of(button);
    }

    @Override
    public List<? extends Selectable> narratables() {
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
