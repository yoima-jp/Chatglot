package io.github.chatglot.fabric.mixin;

import io.github.chatglot.fabric.BookTranslationController;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin {
    @Redirect(
        method = "visitText(Lnet/minecraft/client/gui/ActiveTextCollector;Z)V",
        at = @At(value = "INVOKE", target = "Ljava/util/List;size()I")
    )
    private int chatglot$skipVanillaPageBody(List<FormattedCharSequence> lines) {
        // The vanilla body loop also registers link hover and click events. The translated
        // page is drawn as plain text instead, so none of the source page's commands or links
        // can be activated while the translation is visible.
        return BookTranslationController.isTranslationVisible((BookViewScreen) (Object) this) ? 0 : lines.size();
    }
}
