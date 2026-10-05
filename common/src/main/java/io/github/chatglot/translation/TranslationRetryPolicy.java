package io.github.chatglot.translation;

import io.github.chatglot.translation.TranslationException.Kind;
import java.time.Duration;
import java.util.Optional;

public final class TranslationRetryPolicy {
    public static final int MAX_RETRIES = 2;
    private static final long MAX_DELAY_MILLIS = 10_000;
    private static final long MAX_JITTER_MILLIS = 250;
    private static final long[] BASE_DELAYS_MILLIS = { 500, 1_500 };

    private TranslationRetryPolicy() { }

    public static boolean shouldRetry(TranslationException exception, int retriesUsed) {
        if (exception == null || retriesUsed >= MAX_RETRIES || retriesUsed < 0) {
            return false;
        }
        return switch (exception.kind()) {
            case RATE_LIMITED, SERVER, NETWORK -> true;
            // A response-read timeout may follow a completed billable request, so retry only connection timeouts.
            case TIMEOUT -> exception.connectionTimeout();
            default -> false;
        };
    }

    public static long delayMillis(int retryNumber, Optional<Duration> retryAfter, long jitterMillis) {
        if (retryAfter != null && retryAfter.isPresent()) {
            Duration duration = retryAfter.get();
            long millis;
            try {
                millis = duration.toMillis();
            } catch (ArithmeticException e) {
                millis = MAX_DELAY_MILLIS;
            }
            return Math.max(0, Math.min(MAX_DELAY_MILLIS, millis));
        }
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber must be positive");
        }
        long base = BASE_DELAYS_MILLIS[Math.min(retryNumber, BASE_DELAYS_MILLIS.length) - 1];
        long jitter = Math.max(0, Math.min(MAX_JITTER_MILLIS, jitterMillis));
        return Math.min(MAX_DELAY_MILLIS, base + jitter);
    }

    public static int remainingRequestTimeoutSeconds(long elapsedNanos, long budgetNanos, long delayMillis) {
        long remainingNanos = budgetNanos - Math.max(0, elapsedNanos) - Duration.ofMillis(Math.max(0, delayMillis)).toNanos();
        if (remainingNanos <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, remainingNanos / 1_000_000_000L);
    }
}
