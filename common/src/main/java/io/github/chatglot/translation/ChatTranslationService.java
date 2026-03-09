package io.github.chatglot.translation;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotConfigManager;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatTranslationService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/TranslationService");

    private final ChatglotConfigManager configManager;
    private final TranslationProviderRegistry providerRegistry;
    private final Path configDir;
    private final Path gameDir;
    private final ExecutorService executor;

    public ChatTranslationService(
        ChatglotConfigManager configManager,
        TranslationProviderRegistry providerRegistry,
        Path configDir,
        Path gameDir
    ) {
        this.configManager = configManager;
        this.providerRegistry = providerRegistry;
        this.configDir = configDir;
        this.gameDir = gameDir;
        this.executor = Executors.newSingleThreadExecutor(new ChatglotThreadFactory());
    }

    public CompletableFuture<TranslationResult> translate(
        String text,
        String targetLanguage,
        String sourceLanguageHint,
        boolean automatic
    ) {
        return CompletableFuture.supplyAsync(() -> {
            ChatglotConfig config = configManager.get();
            config.sanitize();

            if (!config.enabled) {
                throw new CompletionException(new TranslationException("Chatglot is disabled in config."));
            }

            String resolvedTargetLanguage = LanguageUtil.normalizeTargetLanguage(targetLanguage);
            if (resolvedTargetLanguage.isBlank()) {
                resolvedTargetLanguage = LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage);
            }
            if (resolvedTargetLanguage.isBlank()) {
                throw new CompletionException(new TranslationException("Target language is empty."));
            }

            TranslationRequest request = new TranslationRequest(
                text,
                resolvedTargetLanguage,
                LanguageUtil.normalize(sourceLanguageHint),
                automatic
            );

            String providerId = resolveProviderId(config.provider);
            Optional<TranslationProvider> providerOptional = providerRegistry.get(providerId);
            TranslationProvider provider = providerOptional.orElseThrow(
                () -> new CompletionException(
                    new TranslationException(
                        "Unsupported provider. Configured='" + config.provider + "', resolved='" + providerId + "'"
                    )
                )
            );

            try {
                return provider.translate(request, config, configDir, gameDir);
            } catch (TranslationException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(new TranslationException("Unexpected translation failure", e));
            }
        }, executor);
    }

    private static String resolveProviderId(String configuredProviderId) {
        if (configuredProviderId == null || configuredProviderId.isBlank()) {
            return "gas";
        }

        String normalized = configuredProviderId.trim().toLowerCase(Locale.ROOT);
        if (ChatglotConfig.DEFAULT_PROVIDER.equals(normalized)) {
            return "gas";
        }
        return normalized;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class ChatglotThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chatglot-translator");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught async translation error", e));
            return thread;
        }
    }
}
