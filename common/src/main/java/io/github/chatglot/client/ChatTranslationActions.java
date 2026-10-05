package io.github.chatglot.client;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.TranslationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatTranslationActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/Actions");
    private static final TranslationErrorDisplayLimiter ERROR_DISPLAY_LIMITER = new TranslationErrorDisplayLimiter();

    private ChatTranslationActions() {
    }

    public static void translateAndPublish(String originalText, String sourceLanguageHint, boolean automatic) {
        translateAndPublish(originalText, null, sourceLanguageHint, automatic, null);
    }

    public static void translateAndPublish(
        String originalText,
        Component originalMessage,
        String sourceLanguageHint,
        boolean automatic,
        MessageSignature originalSignature
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
                TranslationException failure = TranslationErrorMessages.translationException(unwrapped);
                TranslationException.Kind kind = failure == null
                    ? TranslationException.Kind.UNKNOWN
                    : failure.kind();
                String providerId = failure != null && failure.providerId() != null
                    ? failure.providerId()
                    : config.provider;
                boolean suppressedByBreaker = failure != null && failure.suppressedByBreaker();
                boolean breakerTriggered = failure != null && failure.claimBreakerNotice();
                if (ERROR_DISPLAY_LIMITER.shouldDisplay(
                    providerId, kind, automatic, suppressedByBreaker, breakerTriggered
                )) {
                    ChatOutput.post(TranslationErrorMessages.chat(unwrapped, config.provider, breakerTriggered));
                }
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
        while ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
            && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }
}
