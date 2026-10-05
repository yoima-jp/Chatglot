package io.github.chatglot.translation;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotConfigManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatTranslationService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/TranslationService");

    private final ChatglotConfigManager configManager;
    private final TranslationProviderRegistry providerRegistry;
    private final Path configDir;
    private final Path gameDir;
    private final TranslationCircuitBreaker circuitBreaker;
    private final ConcurrentHashMap<TranslationKey, CompletableFuture<TranslationResult>> inFlight = new ConcurrentHashMap<>();
    private final Object executorLock = new Object();
    private ExecutorService executor;
    private int executorParallelism;

    public ChatTranslationService(
        ChatglotConfigManager configManager,
        TranslationProviderRegistry providerRegistry,
        Path configDir,
        Path gameDir
    ) {
        this(configManager, providerRegistry, configDir, gameDir, new TranslationCircuitBreaker());
    }

    public ChatTranslationService(
        ChatglotConfigManager configManager,
        TranslationProviderRegistry providerRegistry,
        Path configDir,
        Path gameDir,
        TranslationCircuitBreaker circuitBreaker
    ) {
        this.configManager = configManager;
        this.providerRegistry = providerRegistry;
        this.configDir = configDir;
        this.gameDir = gameDir;
        this.circuitBreaker = circuitBreaker;
        this.executorParallelism = 0;
    }

    public CompletableFuture<TranslationResult> translate(
        String text,
        String targetLanguage,
        String sourceLanguageHint,
        boolean automatic
    ) {
        ChatglotConfig config = configManager.get();
        config.sanitize();
        if (!config.enabled) {
            return failed(TranslationException.classified(
                TranslationException.Kind.DISABLED, "Chatglot is disabled in config.", null, null, null, null, null
            ));
        }

        String resolvedTargetLanguage = LanguageUtil.normalizeTargetLanguage(targetLanguage);
        if (resolvedTargetLanguage.isBlank()) {
            resolvedTargetLanguage = LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage);
        }
        String providerId = resolveProviderId(config.provider);
        if (resolvedTargetLanguage.isBlank()) {
            return failed(TranslationException.classified(
                TranslationException.Kind.CONFIG_MISSING,
                "Target language is empty.", providerId, null, null, null, null
            ));
        }

        Optional<TranslationProvider> providerOptional = providerRegistry.get(providerId);
        if (providerOptional.isEmpty()) {
            return failed(TranslationException.classified(
                TranslationException.Kind.CONFIG_MISSING,
                "Configured translation provider is not supported.",
                providerId, null, null, "configured=" + config.provider + ", resolved=" + providerId, null
            ));
        }

        TranslationProvider provider = providerOptional.orElseThrow();
        String resolvedSourceHint = LanguageUtil.normalize(sourceLanguageHint);
        TranslationKey key = new TranslationKey(text, resolvedTargetLanguage, resolvedSourceHint, providerId);
        CompletableFuture<TranslationResult> shared = inFlight.get(key);
        if (shared != null) {
            return detached(shared);
        }

        CompletableFuture<TranslationResult> created = new CompletableFuture<>();
        shared = inFlight.putIfAbsent(key, created);
        if (shared != null) {
            return detached(shared);
        }

        if (automatic) {
            Optional<TranslationException> breakerFailure = circuitBreaker.failureIfBlocked(providerId, config);
            if (breakerFailure.isPresent()) {
                completeFailure(key, created, breakerFailure.orElseThrow());
                return detached(created);
            }
        }

        TranslationRequest request = new TranslationRequest(text, resolvedTargetLanguage, resolvedSourceHint, automatic);
        ExecutorService currentExecutor = ensureExecutor(config.maxConcurrentTranslations);
        try {
            currentExecutor.execute(() -> executeTranslation(key, created, provider, request, config, providerId));
        } catch (RuntimeException e) {
            completeFailure(key, created, TranslationException.caused(
                TranslationException.Kind.UNKNOWN, "Translation could not be scheduled.", providerId, e
            ));
        }
        return detached(created);
    }

    private void executeTranslation(
        TranslationKey key,
        CompletableFuture<TranslationResult> shared,
        TranslationProvider provider,
        TranslationRequest request,
        ChatglotConfig config,
        String providerId
    ) {
        long startedAtNanos = System.nanoTime();
        long budgetNanos = Duration.ofSeconds(config.requestTimeoutSeconds).toNanos();
        int retriesUsed = 0;
        ChatglotConfig attemptConfig = config;
        while (true) {
            try {
                TranslationResult result = provider.translate(request, attemptConfig, configDir, gameDir);
                circuitBreaker.recordSuccess(providerId);
                inFlight.remove(key, shared);
                shared.complete(result);
                return;
            } catch (TranslationException exception) {
                TranslationException failure = exception.providerId() == null
                    ? exception.withProviderId(providerId)
                    : exception;
                long elapsedNanos = System.nanoTime() - startedAtNanos;
                if (!TranslationRetryPolicy.shouldRetry(failure, retriesUsed)) {
                    completeFailure(key, shared, recordFailure(providerId, config, failure));
                    return;
                }

                long delayMillis = TranslationRetryPolicy.delayMillis(
                    retriesUsed + 1,
                    failure.retryAfter(),
                    ThreadLocalRandom.current().nextLong(251)
                );
                int remainingSeconds = TranslationRetryPolicy.remainingRequestTimeoutSeconds(
                    elapsedNanos, budgetNanos, delayMillis
                );
                // Codex keeps a five-second floor for each fallback request, so shorter remaining budgets cannot be honored.
                if (remainingSeconds < ("codex".equals(providerId) ? 5 : 1)) {
                    completeFailure(key, shared, recordFailure(providerId, config, failure));
                    return;
                }

                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        completeFailure(key, shared, TranslationException.caused(
                            TranslationException.Kind.UNKNOWN,
                            "Translation retry was interrupted.",
                            providerId,
                            interrupted
                        ));
                        return;
                    }
                }

                remainingSeconds = TranslationRetryPolicy.remainingRequestTimeoutSeconds(
                    System.nanoTime() - startedAtNanos, budgetNanos, 0
                );
                if (remainingSeconds < ("codex".equals(providerId) ? 5 : 1)) {
                    completeFailure(key, shared, recordFailure(providerId, config, failure));
                    return;
                }

                attemptConfig = config.copy();
                attemptConfig.requestTimeoutSeconds = remainingSeconds;
                retriesUsed++;
            } catch (Exception exception) {
                completeFailure(key, shared, TranslationException.caused(
                    TranslationException.Kind.UNKNOWN, "Unexpected translation failure.", providerId, exception
                ));
                return;
            }
        }
    }

    private void completeFailure(
        TranslationKey key,
        CompletableFuture<TranslationResult> shared,
        TranslationException failure
    ) {
        inFlight.remove(key, shared);
        shared.completeExceptionally(failure);
    }

    private TranslationException recordFailure(
        String providerId,
        ChatglotConfig config,
        TranslationException failure
    ) {
        return circuitBreaker.recordFailureAndReportTransition(providerId, config, failure)
            ? failure.asBreakerTriggered()
            : failure;
    }

    private static CompletableFuture<TranslationResult> failed(TranslationException exception) {
        return CompletableFuture.failedFuture(exception);
    }

    private static CompletableFuture<TranslationResult> detached(CompletableFuture<TranslationResult> shared) {
        // Cancellation belongs to each caller and must not cancel the shared provider request.
        return shared.thenApply(result -> result);
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
        synchronized (executorLock) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
                executorParallelism = 0;
            }
        }
    }

    private ExecutorService ensureExecutor(int parallelism) {
        int normalizedParallelism = Math.max(1, parallelism);
        synchronized (executorLock) {
            if (executor != null && executorParallelism == normalizedParallelism) {
                return executor;
            }

            if (executor != null) {
                executor.shutdown();
            }

            executor = Executors.newFixedThreadPool(normalizedParallelism, new ChatglotThreadFactory());
            executorParallelism = normalizedParallelism;
            return executor;
        }
    }

    private record TranslationKey(String text, String targetLanguage, String sourceLanguageHint, String providerId) { }

    private static final class ChatglotThreadFactory implements ThreadFactory {
        private final AtomicInteger threadCounter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chatglot-translator-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught async translation error", e));
            return thread;
        }
    }
}
