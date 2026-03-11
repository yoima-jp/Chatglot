package io.github.chatglot.client;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.TranslationException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatTranslationActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/Actions");

    private ChatTranslationActions() {
    }

    public static void translateAndPublish(String originalText, String sourceLanguageHint, boolean automatic) {
        translateAndPublish(originalText, null, sourceLanguageHint, automatic, null);
    }

    public static void translateAndPublish(
        String originalText,
        Text originalMessage,
        String sourceLanguageHint,
        boolean automatic,
        MessageSignatureData originalSignature
    ) {
        if (originalText == null || originalText.isBlank()) {
            return;
        }

        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();
        String resolvedTargetLanguage = LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage);
        String resolvedSourceLanguageHint = sourceLanguageHint;
        if (resolvedSourceLanguageHint == null || resolvedSourceLanguageHint.isBlank()) {
            Optional<String> detected = runtime.languageDetectorService().detectLanguage(originalText);
            resolvedSourceLanguageHint = detected.orElse("");
        }
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            originalMessage,
            originalText,
            config.preserveLeadingSpeakerPrefix
        );
        runtime.translationService()
            .translate(template.markedText(), resolvedTargetLanguage, resolvedSourceLanguageHint, automatic)
            .thenAccept(result -> ChatOutput.postTranslation(result, originalText, originalSignature, template))
            .exceptionally(error -> {
                Throwable unwrapped = unwrap(error);
                LOGGER.warn("Translation failed", unwrapped);
                String message = unwrapped.getMessage() == null ? "translation failed" : unwrapped.getMessage();
                ChatOutput.postError(message);
                return null;
            });
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        if (throwable instanceof TranslationException) {
            return throwable;
        }
        return throwable;
    }
}
