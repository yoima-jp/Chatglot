package io.github.chatglot.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.translation.TranslationException.Kind;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TranslationErrorDisplayLimiterTest {
    @Test
    void limitsEachProviderAndKindToOneAutomaticNoticePerThirtySeconds() {
        AtomicLong now = new AtomicLong(1_000);
        TranslationErrorDisplayLimiter limiter = new TranslationErrorDisplayLimiter(now::get);

        assertTrue(limiter.shouldDisplay("google", Kind.AUTH, true, false));
        assertFalse(limiter.shouldDisplay("google", Kind.AUTH, true, false));
        assertTrue(limiter.shouldDisplay("deepl", Kind.AUTH, true, false));
        assertTrue(limiter.shouldDisplay("google", Kind.NETWORK, true, false));

        now.addAndGet(29_999);
        assertFalse(limiter.shouldDisplay("google", Kind.AUTH, true, false));
        now.incrementAndGet();
        assertTrue(limiter.shouldDisplay("google", Kind.AUTH, true, false));
    }

    @Test
    void manualFailuresAlwaysDisplayAndBreakerSuppressionsNeverDisplayAutomatically() {
        TranslationErrorDisplayLimiter limiter = new TranslationErrorDisplayLimiter(() -> 10);

        assertTrue(limiter.shouldDisplay("gas", Kind.RATE_LIMITED, false, true));
        assertFalse(limiter.shouldDisplay("gas", Kind.RATE_LIMITED, true, true));
        assertTrue(limiter.shouldDisplay("gas", Kind.RATE_LIMITED, false, false));
    }

    @Test
    void reportsANewBreakerPauseEvenIfThatErrorWasRecentlyDisplayed() {
        TranslationErrorDisplayLimiter limiter = new TranslationErrorDisplayLimiter(() -> 10);

        assertTrue(limiter.shouldDisplay("google", Kind.AUTH, true, false));
        assertTrue(limiter.shouldDisplay("google", Kind.AUTH, true, false, true));
        assertFalse(limiter.shouldDisplay("google", Kind.AUTH, true, false));
    }
}
