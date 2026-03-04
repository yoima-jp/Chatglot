package io.github.chatglot.mixin;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.ChatMessagePipelineGuard;
import io.github.chatglot.client.ChatTranslationActions;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text chatglot$appendTranslateButton(Text message) {
        if (!ChatglotRuntime.isInitialized() || ChatMessagePipelineGuard.isSuppressed()) {
            return message;
        }

        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        if (!config.enabled || !config.appendTranslateButton) {
            return message;
        }

        String plain = message.getString();
        if (plain.isBlank() || plain.startsWith(ChatglotConstants.INTERNAL_PREFIX)) {
            return message;
        }

        int id = ChatglotRuntime.get().requestStore().register(plain);
        MutableText button = Text.literal(" [" + config.translateButtonLabel + "]")
            .setStyle(
                Style.EMPTY
                    .withColor(Formatting.AQUA)
                    .withBold(true)
                    .withClickEvent(new ClickEvent.RunCommand("/chatglot translate " + id))
                    .withHoverEvent(new HoverEvent.ShowText(Text.translatable("chatglot.translate_button.hover")))
            );

        return Text.empty().append(message.copy()).append(button);
    }

    @Inject(
        method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        at = @At("TAIL")
    )
    private void chatglot$autoTranslate(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (!ChatglotRuntime.isInitialized() || ChatMessagePipelineGuard.isSuppressed()) {
            return;
        }

        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        if (!config.enabled || !config.autoTranslateEnabled) {
            return;
        }

        String plain = stripTranslateButtonSuffix(message.getString(), config.translateButtonLabel);
        if (plain.isBlank() || plain.startsWith(ChatglotConstants.INTERNAL_PREFIX)) {
            return;
        }

        String detectedLanguage = ChatglotRuntime.get()
            .languageDetectorService()
            .detectLanguage(plain)
            .orElse("");

        if (detectedLanguage.isBlank() || LanguageUtil.isSameLanguage(detectedLanguage, config.targetLanguage)) {
            return;
        }

        ChatTranslationActions.translateAndPublish(plain, detectedLanguage, true);
    }

    private static String stripTranslateButtonSuffix(String value, String buttonLabel) {
        String suffix = " [" + buttonLabel + "]";
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }
}
