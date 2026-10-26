package io.github.chatglot.translation;

import com.google.gson.JsonObject;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotConfigManager;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URI;
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
    private final TranslationCache translationCache;
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
        this(
            configManager,
            providerRegistry,
            configDir,
            gameDir,
            circuitBreaker,
            new TranslationCache(
                io.github.chatglot.config.ChatglotStoragePaths.resolveTranslationCacheFile(configDir),
                configManager.get().translationCacheMaxEntries
            )
        );
    }

    public ChatTranslationService(
        ChatglotConfigManager configManager,
        TranslationProviderRegistry providerRegistry,
        Path configDir,
        Path gameDir,
        TranslationCircuitBreaker circuitBreaker,
        TranslationCache translationCache
    ) {
        this.configManager = configManager;
        this.providerRegistry = providerRegistry;
        this.configDir = configDir;
        this.gameDir = gameDir;
        this.circuitBreaker = circuitBreaker;
        this.translationCache = translationCache;
        this.executorParallelism = 0;
    }

    public CompletableFuture<TranslationResult> translate(
        String text,
        String targetLanguage,
        String sourceLanguageHint,
        boolean automatic
    ) {
        return translate(text, targetLanguage, sourceLanguageHint, automatic, true);
    }

    public CompletableFuture<TranslationResult> translateWithoutCache(
        String text,
        String targetLanguage,
        String sourceLanguageHint,
        boolean automatic
    ) {
        return translate(text, targetLanguage, sourceLanguageHint, automatic, false);
    }

    private CompletableFuture<TranslationResult> translate(
        String text,
        String targetLanguage,
        String sourceLanguageHint,
        boolean automatic,
        boolean useCache
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
        // The settings screen writes draft values into the live config object in place, so a request
        // waiting in the executor could otherwise run with newer settings than its cache key describes
        // and persist a new-model result under the old-model key. Freeze the settings at acceptance and
        // use the snapshot for both the cache key and the provider call. The live object is still used
        // where identity matters (in-flight dedup and settings-bound circuit breaker blocks).
        ChatglotConfig settings = config.copy();
        String resolvedSourceHint = LanguageUtil.normalize(sourceLanguageHint);
        TranslationCache.Key cacheKey = new TranslationCache.Key(
            providerId,
            resolvedSourceHint,
            resolvedTargetLanguage,
            text,
            outputSettingsFingerprint(providerId, settings)
        );
        TranslationKey key = new TranslationKey(cacheKey, config);
        if (useCache && settings.translationCacheEnabled) {
            Optional<TranslationCache.Entry> cached = translationCache.get(cacheKey, settings.translationCacheMaxEntries);
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(toResult(cacheKey, cached.orElseThrow()));
            }
        }
        CompletableFuture<TranslationResult> shared = inFlight.get(key);
        if (shared != null) {
            return detached(shared);
        }

        CompletableFuture<TranslationResult> created = new CompletableFuture<>();
        shared = inFlight.putIfAbsent(key, created);
        if (shared != null) {
            return detached(shared);
        }

        // A request may have filled the persistent cache between the first lookup and this map insertion.
        if (useCache && settings.translationCacheEnabled) {
            Optional<TranslationCache.Entry> cached = translationCache.get(cacheKey, settings.translationCacheMaxEntries);
            if (cached.isPresent()) {
                inFlight.remove(key, created);
                created.complete(toResult(cacheKey, cached.orElseThrow()));
                return detached(created);
            }
        }

        if (automatic) {
            Optional<TranslationException> breakerFailure = circuitBreaker.failureIfBlocked(providerId, config);
            if (breakerFailure.isPresent()) {
                completeFailure(key, created, breakerFailure.orElseThrow());
                return detached(created);
            }
        }

        TranslationRequest request = new TranslationRequest(text, resolvedTargetLanguage, resolvedSourceHint, automatic);
        ExecutorService currentExecutor = ensureExecutor(settings.maxConcurrentTranslations);
        try {
            currentExecutor.execute(() -> executeTranslation(
                key, created, cacheKey, provider, request, config, settings, providerId, useCache
            ));
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
        TranslationCache.Key cacheKey,
        TranslationProvider provider,
        TranslationRequest request,
        ChatglotConfig identityConfig,
        ChatglotConfig config,
        String providerId,
        boolean useCache
    ) {
        long startedAtNanos = System.nanoTime();
        long budgetNanos = Duration.ofSeconds(config.requestTimeoutSeconds).toNanos();
        int retriesUsed = 0;
        ChatglotConfig attemptConfig = config;
        while (true) {
            if (request.automatic()) {
                Optional<TranslationException> breakerFailure = circuitBreaker.failureIfBlocked(providerId, identityConfig);
                if (breakerFailure.isPresent()) {
                    completeFailure(key, shared, breakerFailure.orElseThrow());
                    return;
                }
            }
            try {
                TranslationResult result = provider.translate(request, attemptConfig, configDir, gameDir);
                circuitBreaker.recordSuccess(providerId);
                if (useCache && config.translationCacheEnabled && result != null
                    && result.translatedText() != null && !result.translatedText().isBlank()) {
                    translationCache.put(
                        cacheKey,
                        new TranslationCache.Entry(
                            result.translatedText(),
                            result.detectedSourceLanguage() == null ? "" : result.detectedSourceLanguage(),
                            System.currentTimeMillis()
                        ),
                        config.translationCacheMaxEntries
                    );
                }
                inFlight.remove(key, shared);
                shared.complete(result);
                return;
            } catch (TranslationException exception) {
                TranslationException failure = exception.providerId() == null
                    ? exception.withProviderId(providerId)
                    : exception;
                long elapsedNanos = System.nanoTime() - startedAtNanos;
                if (!TranslationRetryPolicy.shouldRetry(failure, retriesUsed)) {
                    completeFailure(key, shared, recordFailure(providerId, identityConfig, failure));
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
                    completeFailure(key, shared, recordFailure(providerId, identityConfig, failure));
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
                    completeFailure(key, shared, recordFailure(providerId, identityConfig, failure));
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
            return "google_web";
        }

        String normalized = configuredProviderId.trim().toLowerCase(Locale.ROOT);
        if (ChatglotConfig.DEFAULT_PROVIDER.equals(normalized)) {
            return "google_web";
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
        translationCache.shutdown();
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

    private static TranslationResult toResult(TranslationCache.Key key, TranslationCache.Entry entry) {
        return new TranslationResult(entry.translatedText(), entry.detectedSourceLanguage(), key.providerId());
    }

    private static String outputSettingsFingerprint(String providerId, ChatglotConfig config) {
        JsonObject settings = new JsonObject();
        switch (providerId) {
            case "gas" -> settings.addProperty("url", safeEndpoint(config.gasWebAppUrl));
            case "deepl" -> settings.addProperty("free", config.deeplUseFreeApi);
            case "codex" -> {
                settings.addProperty("model", config.codexModel);
                settings.addProperty("effort", config.codexReasoningEffort);
                settings.addProperty("summary", config.codexReasoningSummary);
            }
            case "openai" -> settings.addProperty("model", config.openaiModel);
            case "custom_llm" -> {
                settings.addProperty("url", safeEndpoint(config.openaiCompatibleBaseUrl));
                settings.addProperty("protocol", config.openaiCompatibleProtocol);
                settings.addProperty("model", config.openaiCompatibleModel);
            }
            case "gemini" -> settings.addProperty("model", config.geminiModel);
            case "anthropic" -> settings.addProperty("model", config.anthropicModel);
            case "azure" -> {
                settings.addProperty("endpoint", safeEndpoint(config.azureTranslatorEndpoint));
                settings.addProperty("region", config.azureTranslatorRegion);
            }
            case "translategemma_local" -> {
                settings.addProperty("baseUrl", safeEndpoint(config.localBackendBaseUrl));
                settings.addProperty("port", config.localBackendPort);
                settings.addProperty("modelAlias", config.localModelAlias);
                settings.addProperty("modelPath", config.localModelPath);
                settings.addProperty("modelFileName", config.localModelFileName);
                // With an empty modelPath, the model is resolved from the shared directory.
                settings.addProperty("sharedDirectory", config.localBackendSharedDirectory);
            }
            default -> { }
        }
        return settings.toString();
    }

    private static String safeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            // Keep the endpoint path to distinguish deployments/routes, but omit user info and query
            // parameters because custom URLs can put credentials in either location.
            return new URI(
                uri.getScheme().toLowerCase(Locale.ROOT),
                null,
                uri.getHost().toLowerCase(Locale.ROOT),
                uri.getPort(),
                uri.getPath(),
                null,
                null
            ).toASCIIString();
        } catch (Exception ignored) {
            return "";
        }
    }

    // Saving or reloading config replaces the instance, and ChatglotConfig keeps identity equality.
    // Including it prevents a request made after a model, endpoint or key change from joining an
    // in-flight provider call that still uses the previous settings.
    private record TranslationKey(TranslationCache.Key cacheKey, ChatglotConfig config) { }

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
