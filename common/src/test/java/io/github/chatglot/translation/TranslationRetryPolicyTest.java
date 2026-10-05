package io.github.chatglot.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.translation.TranslationException.Kind;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TranslationRetryPolicyTest {
    @Test
    void retriesOnlyTransientFailuresAndConnectionTimeouts() {
        assertTrue(TranslationRetryPolicy.shouldRetry(failure(Kind.RATE_LIMITED), 0));
        assertTrue(TranslationRetryPolicy.shouldRetry(failure(Kind.SERVER), 0));
        assertTrue(TranslationRetryPolicy.shouldRetry(failure(Kind.NETWORK), 0));
        assertFalse(TranslationRetryPolicy.shouldRetry(failure(Kind.TIMEOUT), 0));
        assertTrue(TranslationRetryPolicy.shouldRetry(failure(Kind.TIMEOUT).asConnectionTimeout(), 0));
        assertFalse(TranslationRetryPolicy.shouldRetry(failure(Kind.NETWORK), TranslationRetryPolicy.MAX_RETRIES));
        assertFalse(TranslationRetryPolicy.shouldRetry(failure(Kind.AUTH), 0));
    }

    @Test
    void computesBackoffRetryAfterCapAndRemainingBudget() {
        assertEquals(500, TranslationRetryPolicy.delayMillis(1, Optional.empty(), 0));
        assertEquals(1_750, TranslationRetryPolicy.delayMillis(2, Optional.empty(), 250));
        assertEquals(10_000, TranslationRetryPolicy.delayMillis(1, Optional.of(Duration.ofSeconds(12)), 100));
        assertEquals(2_000, TranslationRetryPolicy.delayMillis(1, Optional.of(Duration.ofSeconds(2)), 100));
        assertEquals(4, TranslationRetryPolicy.remainingRequestTimeoutSeconds(
            Duration.ofMillis(200).toNanos(), Duration.ofSeconds(5).toNanos(), 500
        ));
        assertEquals(0, TranslationRetryPolicy.remainingRequestTimeoutSeconds(
            Duration.ofSeconds(4).toNanos(), Duration.ofSeconds(5).toNanos(), 1_000
        ));
    }

    private static TranslationException failure(Kind kind) {
        return TranslationException.classified(kind, "failure", "provider", null, null, null, null);
    }
}
