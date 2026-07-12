package io.github.chatglot.mixin;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.ChatMessagePipelineGuard;
import io.github.chatglot.client.ChatTranslationActions;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Component chatglot$appendTranslateButton(
        Component message,
        Component originalMessage,
        MessageSignature signature,
        GuiMessageSource source,
        GuiMessageTag indicator
    ) {
        if (source == GuiMessageSource.SYSTEM_CLIENT) {
            return message;
        }

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

        int id = ChatglotRuntime.get().requestStore().register(plain, message, signature);
        MutableComponent button = Component.literal(" " + config.translateButtonLabel)
            .setStyle(
                Style.EMPTY
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.RunCommand("/chatglot translate " + id))
                    .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chatglot.translate_button.hover")))
            );

        return Component.empty().append(message.copy()).append(button);
    }

    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("TAIL")
    )
    private void chatglot$autoTranslate(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag indicator, CallbackInfo ci) {
        if (source == GuiMessageSource.SYSTEM_CLIENT) {
            return;
        }

        if (!ChatglotRuntime.isInitialized() || ChatMessagePipelineGuard.isSuppressed()) {
            return;
        }

        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();
        if (!config.enabled || !config.autoTranslateEnabled || !isAutoTranslateProviderSupported(config.provider)) {
            return;
        }

        Component originalMessage = stripTranslateButton(message, config.translateButtonLabel);
        String plain = originalMessage == null ? stripTranslateButtonSuffix(message.getString(), config.translateButtonLabel) : originalMessage.getString();
        if (plain.isBlank() || plain.startsWith(ChatglotConstants.INTERNAL_PREFIX)) {
            return;
        }

        String resolvedTargetLanguage = LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage);
        if (resolvedTargetLanguage.isBlank()) {
            return;
        }

        runtime.languageDetectorService()
            .detectLanguageAsync(plain)
            .thenAccept(detectedLanguage -> {
                String detected = detectedLanguage.orElse("");
                if (!detected.isBlank() && LanguageUtil.isSameLanguage(detected, resolvedTargetLanguage)) {
                    return;
                }
                ChatTranslationActions.translateAndPublish(plain, originalMessage, detected, true, signature);
            });
    }

    private static Component stripTranslateButton(Component message, String buttonLabel) {
        if (message == null) {
            return null;
        }

        MutableComponent copy = message.copy();
        if (copy.getSiblings().isEmpty()) {
            return copy;
        }

        Component lastSibling = copy.getSiblings().get(copy.getSiblings().size() - 1);
        if (!isTranslateButton(lastSibling, buttonLabel)) {
            return copy;
        }

        copy.getSiblings().remove(copy.getSiblings().size() - 1);
        return copy;
    }

    private static boolean isTranslateButton(Component text, String buttonLabel) {
        if (text == null || buttonLabel == null || buttonLabel.isBlank()) {
            return false;
        }

        String value = text.getString();
        boolean matchesLabel = false;
        for (String candidateLabel : resolveAcceptedButtonLabels(buttonLabel)) {
            if ((" " + candidateLabel).equals(value) || (" [" + candidateLabel + "]").equals(value)) {
                matchesLabel = true;
                break;
            }
        }
        if (!matchesLabel) {
            return false;
        }

        ClickEvent clickEvent = text.getStyle().getClickEvent();
        return clickEvent instanceof ClickEvent.RunCommand runCommand
            && runCommand.command().startsWith("/chatglot translate ");
    }

    private static String stripTranslateButtonSuffix(String value, String buttonLabel) {
        String suffix = " " + buttonLabel;
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }

        String legacySuffix = " [" + buttonLabel + "]";
        if (value.endsWith(legacySuffix)) {
            return value.substring(0, value.length() - legacySuffix.length());
        }
        return value;
    }

    private static List<String> resolveAcceptedButtonLabels(String buttonLabel) {
        List<String> labels = new ArrayList<>();
        if (buttonLabel != null && !buttonLabel.isBlank()) {
            labels.add(buttonLabel);
        }
        if (ChatglotConfig.DEFAULT_TRANSLATE_BUTTON_LABEL.equals(buttonLabel)) {
            labels.add(ChatglotConfig.LEGACY_TRANSLATE_BUTTON_LABEL);
        }
        return labels;
    }

    private static boolean isAutoTranslateProviderSupported(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        return !ChatglotConfig.DEFAULT_PROVIDER.equalsIgnoreCase(providerId.trim());
    }
}
