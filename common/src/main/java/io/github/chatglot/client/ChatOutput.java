package io.github.chatglot.client;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.mixin.ChatHudAccessor;
import io.github.chatglot.translation.TranslationResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;

public final class ChatOutput {
    private ChatOutput() {
    }

    static void postTranslation(
        TranslationResult result,
        String originalText,
        MessageSignature originalSignature,
        StyledTranslationTemplate template
    ) {
        String translatedText = result.translatedText() == null ? "" : result.translatedText();
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        MutableComponent message = Component.empty();
        if (config.showTranslationPrefix) {
            message.append(
                Component.translatable("chatglot.translation.tag").withStyle(ChatFormatting.AQUA)
                    .append(Component.translatable("chatglot.translation.arrow").withStyle(ChatFormatting.GRAY))
            );
        }
        message.append(template.apply(translatedText));
        postTranslation(message, originalText, originalSignature);
    }

    public static void postError(String message) {
        post(Component.literal(ChatglotConstants.INTERNAL_PREFIX + " " + message).withStyle(ChatFormatting.RED));
    }

    public static void postInfo(String message) {
        post(Component.literal(ChatglotConstants.INTERNAL_PREFIX + " " + message).withStyle(ChatFormatting.GRAY));
    }

    public static void post(Component text) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) {
            return;
        }

        client.execute(() -> ChatMessagePipelineGuard.runSuppressed(() -> client.gui.hud.getChat().addClientSystemMessage(text)));
    }

    private static void postTranslation(Component translatedText, String originalText, MessageSignature originalSignature) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) {
            return;
        }

        client.execute(() -> ChatMessagePipelineGuard.runSuppressed(() -> {
            ChatComponent chatHud = client.gui.hud.getChat();
            ChatglotConfig config = ChatglotRuntime.get().configManager().get();
            boolean replaced = false;
            if (config.overwriteOriginalWithTranslation) {
                replaced = replaceOriginalMessage(
                    chatHud,
                    translatedText,
                    originalText,
                    originalSignature,
                    config.translateButtonLabel
                );
            }
            if (!replaced) {
                chatHud.addClientSystemMessage(translatedText);
            }
        }));
    }

    private static boolean replaceOriginalMessage(
        ChatComponent chatHud,
        Component translatedText,
        String originalText,
        MessageSignature originalSignature,
        String buttonLabel
    ) {
        if (!(chatHud instanceof ChatHudAccessor accessor)) {
            return false;
        }

        List<GuiMessage> messages = accessor.chatglot$getMessages();
        if (messages.isEmpty()) {
            return false;
        }

        int targetIndex = findLatestMatchingMessageIndex(messages, originalText, originalSignature, buttonLabel);
        if (targetIndex < 0) {
            return false;
        }

        GuiMessage originalLine = messages.get(targetIndex);
        messages.set(
            targetIndex,
            new GuiMessage(
                originalLine.addedTime(),
                translatedText,
                originalLine.signature(),
                originalLine.source(),
                originalLine.tag()
            )
        );
        accessor.chatglot$invokeRefresh();
        return true;
    }

    private static int findLatestMatchingMessageIndex(
        List<GuiMessage> messages,
        String originalText,
        MessageSignature originalSignature,
        String buttonLabel
    ) {
        int bySignature = findLatestBySignature(messages, originalSignature);
        if (bySignature >= 0) {
            return bySignature;
        }

        if (originalText == null || originalText.isBlank()) {
            return -1;
        }
        return findLatestByOriginalText(messages, originalText, buttonLabel);
    }

    private static int findLatestBySignature(List<GuiMessage> messages, MessageSignature originalSignature) {
        if (originalSignature == null) {
            return -1;
        }

        int selectedIndex = -1;
        int latestCreationTick = Integer.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            GuiMessage line = messages.get(i);
            if (!originalSignature.equals(line.signature())) {
                continue;
            }

            if (line.addedTime() > latestCreationTick) {
                latestCreationTick = line.addedTime();
                selectedIndex = i;
            }
        }
        return selectedIndex;
    }

    private static int findLatestByOriginalText(List<GuiMessage> messages, String originalText, String buttonLabel) {
        int selectedIndex = -1;
        int latestCreationTick = Integer.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            GuiMessage line = messages.get(i);
            String content = line.content().getString();
            if (!matchesOriginal(content, originalText, buttonLabel)) {
                continue;
            }

            if (line.addedTime() > latestCreationTick) {
                latestCreationTick = line.addedTime();
                selectedIndex = i;
            }
        }
        return selectedIndex;
    }

    private static boolean matchesOriginal(String content, String originalText, String buttonLabel) {
        if (content.equals(originalText)) {
            return true;
        }

        for (String candidateLabel : resolveAcceptedButtonLabels(buttonLabel)) {
            if (stripTranslateButtonSuffix(content, candidateLabel).equals(originalText)) {
                return true;
            }
        }
        return false;
    }

    private static String stripTranslateButtonSuffix(String value, String buttonLabel) {
        if (buttonLabel == null || buttonLabel.isBlank() || value == null || value.isEmpty()) {
            return value;
        }

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
}
