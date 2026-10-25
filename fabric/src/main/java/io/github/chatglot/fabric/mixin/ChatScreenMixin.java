package io.github.chatglot.fabric.mixin;

import io.github.chatglot.fabric.ChatInputTranslationController;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Inject(method = "onEdited", at = @At("HEAD"))
    private void chatglot$observeInputEdit(String value, CallbackInfo callbackInfo) {
        // Observe the existing EditBox responder callback without replacing it, so an edit
        // invalidates a preview immediately even if the text is changed back before the next tick.
        ChatInputTranslationController.inputEdited((ChatScreen) (Object) this, value);
    }
}
