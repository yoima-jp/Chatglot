package io.github.chatglot.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException.Kind;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TranslationCircuitBreakerTest {
    @Test
    void rateLimitUsesTheLongerRetryAfterAndExpires() {
        AtomicLong now = new AtomicLong(1_000);
        TranslationCircuitBreaker breaker = new TranslationCircuitBreaker(now::get);
        ChatglotConfig config = new ChatglotConfig();
        TranslationException failure = TranslationException.classified(
            Kind.RATE_LIMITED, "rate limited", "google", 429, Duration.ofSeconds(45), null, null
        );

        breaker.recordFailure("google", config, failure);
        TranslationException suppressed = breaker.failureIfBlocked("google", config).orElseThrow();
        assertEquals(Kind.RATE_LIMITED, suppressed.kind());
        assertTrue(suppressed.suppressedByBreaker());
        now.addAndGet(Duration.ofSeconds(44).toMillis());
        assertTrue(breaker.isBlocked("google", config));
        now.addAndGet(Duration.ofSeconds(1).toMillis());
        assertFalse(breaker.isBlocked("google", config));
    }

    @Test
    void quotaAndConfigBoundFailuresClearOnCooldownOrConfigReplacement() {
        AtomicLong now = new AtomicLong(10);
        TranslationCircuitBreaker breaker = new TranslationCircuitBreaker(now::get);
        ChatglotConfig originalConfig = new ChatglotConfig();
        TranslationException quota = TranslationException.classified(
            Kind.QUOTA, "quota", "deepl", 456, null, null, null
        );
        breaker.recordFailure("deepl", originalConfig, quota);
        assertTrue(breaker.isBlocked("deepl", originalConfig));
        now.addAndGet(Duration.ofMinutes(5).toMillis());
        assertFalse(breaker.isBlocked("deepl", originalConfig));

        TranslationException auth = TranslationException.classified(
            Kind.AUTH, "auth", "anthropic", 403, null, null, null
        );
        breaker.recordFailure("anthropic", originalConfig, auth);
        assertTrue(breaker.isBlocked("anthropic", originalConfig));
        assertFalse(breaker.isBlocked("anthropic", new ChatglotConfig()));
    }

    @Test
    void successClearsAnOpenProviderBlock() {
        TranslationCircuitBreaker breaker = new TranslationCircuitBreaker(() -> 100);
        ChatglotConfig config = new ChatglotConfig();
        breaker.recordFailure("openai", config, TranslationException.classified(
            Kind.CONFIG_MISSING, "key missing", "openai"
        ));

        breaker.recordSuccess("openai");

        assertFalse(breaker.isBlocked("openai", config));
    }
}
