package io.github.chatglot.client;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.mixin.ChatHudAccessor;
import io.github.chatglot.translation.TranslationResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class ChatOutput {
    private ChatOutput() {
    }

    static void postTranslation(
        TranslationResult result,
        String originalText,
        MessageSignatureData originalSignature,
        StyledTranslationTemplate template
    ) {
        String translatedText = result.translatedText() == null ? "" : result.translatedText();
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        MutableText message = Text.empty();
        if (config.showTranslationPrefix) {
            message.append(
                Text.translatable("chatglot.translation.tag").formatted(Formatting.AQUA)
                    .append(Text.translatable("chatglot.translation.arrow").formatted(Formatting.GRAY))
            );
        }
        message.append(template.apply(translatedText));
        postTranslation(message, originalText, originalSignature);
    }

    public static void postError(String message) {
        post(Text.literal(ChatglotConstants.INTERNAL_PREFIX + " " + message).formatted(Formatting.RED));
    }

    public static void postInfo(String message) {
        post(Text.literal(ChatglotConstants.INTERNAL_PREFIX + " " + message).formatted(Formatting.GRAY));
    }

    public static void post(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) {
            return;
        }

        client.execute(() -> ChatMessagePipelineGuard.runSuppressed(() -> client.inGameHud.getChatHud().addMessage(text)));
    }

    private static void postTranslation(Text translatedText, String originalText, MessageSignatureData originalSignature) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) {
            return;
        }

        client.execute(() -> ChatMessagePipelineGuard.runSuppressed(() -> {
            ChatHud chatHud = client.inGameHud.getChatHud();
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
                chatHud.addMessage(translatedText);
            }
        }));
    }

    private static boolean replaceOriginalMessage(
        ChatHud chatHud,
        Text translatedText,
        String originalText,
        MessageSignatureData originalSignature,
        String buttonLabel
    ) {
        if (!(chatHud instanceof ChatHudAccessor accessor)) {
            return false;
        }

        List<ChatHudLine> messages = accessor.chatglot$getMessages();
        if (messages.isEmpty()) {
            return false;
        }

        int targetIndex = findLatestMatchingMessageIndex(messages, originalText, originalSignature, buttonLabel);
        if (targetIndex < 0) {
            return false;
        }

        ChatHudLine originalLine = messages.get(targetIndex);
        messages.set(
            targetIndex,
            new ChatHudLine(originalLine.creationTick(), translatedText, null, originalLine.indicator())
        );
        accessor.chatglot$invokeRefresh();
        return true;
    }

    private static int findLatestMatchingMessageIndex(
        List<ChatHudLine> messages,
        String originalText,
        MessageSignatureData originalSignature,
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

    private static int findLatestBySignature(List<ChatHudLine> messages, MessageSignatureData originalSignature) {
        if (originalSignature == null) {
            return -1;
        }

        int selectedIndex = -1;
        int latestCreationTick = Integer.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            ChatHudLine line = messages.get(i);
            if (!originalSignature.equals(line.signature())) {
                continue;
            }

            if (line.creationTick() > latestCreationTick) {
                latestCreationTick = line.creationTick();
                selectedIndex = i;
            }
        }
        return selectedIndex;
    }

    private static int findLatestByOriginalText(List<ChatHudLine> messages, String originalText, String buttonLabel) {
        int selectedIndex = -1;
        int latestCreationTick = Integer.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            ChatHudLine line = messages.get(i);
            String content = line.content().getString();
            if (!matchesOriginal(content, originalText, buttonLabel)) {
                continue;
            }

            if (line.creationTick() > latestCreationTick) {
                latestCreationTick = line.creationTick();
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
