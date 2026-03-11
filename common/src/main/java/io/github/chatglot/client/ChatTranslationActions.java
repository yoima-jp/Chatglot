package io.github.chatglot.client;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.TranslationException;
import java.util.concurrent.CompletableFuture;
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
        StyledTranslationTemplate template = StyledTranslationTemplate.create(
            originalMessage,
            originalText,
            config.preserveLeadingSpeakerPrefix
        );
        resolveSourceLanguageHint(runtime, config, originalText, sourceLanguageHint)
            .thenCompose(resolvedSourceLanguageHint -> runtime.translationService()
                .translate(template.markedText(), resolvedTargetLanguage, resolvedSourceLanguageHint, automatic)
            )
            .thenAccept(result -> ChatOutput.postTranslation(result, originalText, originalSignature, template))
            .exceptionally(error -> {
                Throwable unwrapped = unwrap(error);
                LOGGER.warn("Translation failed", unwrapped);
                String message = unwrapped.getMessage() == null ? "translation failed" : unwrapped.getMessage();
                ChatOutput.postError(message);
                return null;
            });
    }

    private static CompletableFuture<String> resolveSourceLanguageHint(
        ChatglotRuntime runtime,
        ChatglotConfig config,
        String originalText,
        String sourceLanguageHint
    ) {
        if (sourceLanguageHint != null && !sourceLanguageHint.isBlank()) {
            return CompletableFuture.completedFuture(sourceLanguageHint);
        }
        if (!"translategemma_local".equalsIgnoreCase(config.provider)) {
            return CompletableFuture.completedFuture(sourceLanguageHint);
        }
        return runtime.languageDetectorService()
            .detectLanguageAsync(originalText)
            .thenApply(detected -> detected.orElse(""));
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
