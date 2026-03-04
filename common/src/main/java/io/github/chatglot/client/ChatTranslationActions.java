package io.github.chatglot.client;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.translation.TranslationException;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatTranslationActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/Actions");

    private ChatTranslationActions() {
    }

    public static void translateAndPublish(String originalText, String sourceLanguageHint, boolean automatic) {
        if (originalText == null || originalText.isBlank()) {
            return;
        }

        ChatglotRuntime runtime = ChatglotRuntime.get();
        runtime.translationService()
            .translate(originalText, sourceLanguageHint, automatic)
            .thenAccept(result -> ChatOutput.postTranslation(result, automatic))
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
