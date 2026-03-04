package io.github.chatglot.client;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.translation.TranslationResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class ChatOutput {
    private ChatOutput() {
    }

    public static void postTranslation(TranslationResult result, boolean automatic) {
        boolean showSourceLanguage = ChatglotRuntime.isInitialized()
            && ChatglotRuntime.get().configManager().get().showSourceLanguageTag;

        MutableText prefix = Text.literal(ChatglotConstants.INTERNAL_PREFIX + " ").formatted(Formatting.GRAY)
            .append(Text.literal("[" + result.providerId().toUpperCase() + "] ").formatted(Formatting.DARK_AQUA));

        if (automatic && showSourceLanguage && result.detectedSourceLanguage() != null && !result.detectedSourceLanguage().isBlank()) {
            prefix.append(Text.literal("(" + result.detectedSourceLanguage().toUpperCase() + ") ").formatted(Formatting.DARK_GRAY));
        }

        post(prefix.append(Text.literal(result.translatedText())));
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
}
