package io.github.chatglot.client;

import io.github.chatglot.translation.TranslationException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Decides which automatic translation failures should reach chat. */
public final class TranslationErrorDisplayLimiter {
    // Match the rate-limit breaker cooldown so repeated failures do not flood a single pause window.
    private static final long DISPLAY_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();

    private final LongSupplier clockMillis;
    private final Map<Key, Long> lastDisplayed = new HashMap<>();

    public TranslationErrorDisplayLimiter() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    public TranslationErrorDisplayLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    public synchronized boolean shouldDisplay(
        String providerId,
        TranslationException.Kind kind,
        boolean automatic,
        boolean suppressedByBreaker
    ) {
        return shouldDisplay(providerId, kind, automatic, suppressedByBreaker, false);
    }

    public synchronized boolean shouldDisplay(
        String providerId,
        TranslationException.Kind kind,
        boolean automatic,
        boolean suppressedByBreaker,
        boolean breakerJustTriggered
    ) {
        Key key = new Key(normalize(providerId), kind == null ? TranslationException.Kind.UNKNOWN : kind);
        // A manual request was explicitly initiated, so each failure deserves a response.
        if (!automatic) {
            // Count the visible manual notice so a concurrent automatic duplicate does not echo it immediately.
            lastDisplayed.put(key, clockMillis.getAsLong());
            return true;
        }
        // The first breaker-triggering failure is reported separately; later blocked attempts add no new information.
        if (suppressedByBreaker) {
            return false;
        }

        long now = clockMillis.getAsLong();
        if (breakerJustTriggered) {
            // A new pause must be visible even if the same provider recovered and failed again quickly.
            lastDisplayed.put(key, now);
            return true;
        }
        Long previous = lastDisplayed.get(key);
        if (previous != null && now >= previous && now - previous < DISPLAY_INTERVAL_MILLIS) {
            return false;
        }
        lastDisplayed.put(key, now);
        return true;
    }

    private static String normalize(String providerId) {
        return providerId == null || providerId.isBlank()
            ? "unknown"
            : providerId.trim().toLowerCase(Locale.ROOT);
    }

    private record Key(String providerId, TranslationException.Kind kind) { }
}
