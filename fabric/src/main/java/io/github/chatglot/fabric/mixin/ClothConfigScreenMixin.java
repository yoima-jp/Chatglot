package io.github.chatglot.fabric.mixin;

import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "me.shedaniel.clothconfig2.gui.ClothConfigScreen$ListWidget", remap = false)
public abstract class ClothConfigScreenMixin {
    @Inject(
        method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void chatglot$closeDropdownOnOutsideClick(
        MouseButtonEvent click,
        boolean focused,
        CallbackInfoReturnable<Boolean> cir
    ) {
        Object focusedEntry = chatglot$getFocusedEntry();
        if (!(focusedEntry instanceof DropdownBoxEntry<?> dropdown)) {
            return;
        }

        if (dropdown.isMouseOver(click.x(), click.y())) {
            return;
        }

        dropdown.updateSelected(false);
        dropdown.setFocused(null);
        chatglot$clearFocusedEntry();
    }

    private Object chatglot$getFocusedEntry() {
        try {
            return this.getClass().getMethod("getFocused").invoke(this);
        } catch (ReflectiveOperationException ignored) {
            try {
                return this.getClass().getMethod("method_25399").invoke(this);
            } catch (ReflectiveOperationException ignoredAgain) {
                return null;
            }
        }
    }

    private void chatglot$clearFocusedEntry() {
        try {
            Class<?> elementClass = Class.forName("net.minecraft.client.gui.Element");
            this.getClass().getMethod("setFocused", elementClass).invoke(this, new Object[] { null });
        } catch (ReflectiveOperationException ignored) {
            try {
                Class<?> elementClass = Class.forName("net.minecraft.class_364");
                this.getClass().getMethod("method_25395", elementClass).invoke(this, new Object[] { null });
            } catch (ReflectiveOperationException ignoredAgain) {
                // Ignore if cloth internals change; this hook is best-effort UX behavior.
            }
        }
    }
}
