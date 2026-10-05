package io.github.chatglot.translation;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException.Kind;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class TranslationCircuitBreaker {
    // Thirty seconds spans a short throttling window; quota exhaustion can outlast a single request burst.
    private static final long RATE_LIMIT_COOLDOWN_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final long QUOTA_COOLDOWN_MILLIS = Duration.ofMinutes(5).toMillis();

    private final Map<String, Block> blocks = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    public TranslationCircuitBreaker() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    public TranslationCircuitBreaker(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    public Optional<TranslationException> failureIfBlocked(String providerId, ChatglotConfig config) {
        if (providerId == null) {
            return Optional.empty();
        }
        Block block = blocks.get(providerId);
        if (block == null) {
            return Optional.empty();
        }
        if (block.configBound) {
            if (block.config != config) {
                blocks.remove(providerId, block);
                return Optional.empty();
            }
        } else if (clockMillis.getAsLong() >= block.expiresAtMillis) {
            blocks.remove(providerId, block);
            return Optional.empty();
        }
        return Optional.of(block.failure.asSuppressedByBreaker());
    }

    public void recordFailure(String providerId, ChatglotConfig config, TranslationException failure) {
        if (providerId == null || failure == null) {
            return;
        }
        Kind kind = failure.kind();
        long now = clockMillis.getAsLong();
        Block block = switch (kind) {
            case RATE_LIMITED -> {
                long cooldown = RATE_LIMIT_COOLDOWN_MILLIS;
                if (failure.retryAfter().isPresent()) {
                    cooldown = Math.max(cooldown, durationMillis(failure.retryAfter().orElseThrow()));
                }
                yield Block.timed(failure, saturatingAdd(now, cooldown));
            }
            case QUOTA -> Block.timed(failure, saturatingAdd(now, QUOTA_COOLDOWN_MILLIS));
            case AUTH, CONFIG_MISSING -> Block.configBound(failure, config);
            default -> null;
        };
        if (block != null) {
            blocks.put(providerId, block);
        }
    }

    public void recordSuccess(String providerId) {
        if (providerId != null) {
            blocks.remove(providerId);
        }
    }

    public boolean isBlocked(String providerId, ChatglotConfig config) {
        return failureIfBlocked(providerId, config).isPresent();
    }

    private static long durationMillis(Duration duration) {
        try {
            return Math.max(0, duration.toMillis());
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class Block {
        private final TranslationException failure;
        private final boolean configBound;
        private final ChatglotConfig config;
        private final long expiresAtMillis;

        private Block(TranslationException failure, boolean configBound, ChatglotConfig config, long expiresAtMillis) {
            this.failure = failure;
            this.configBound = configBound;
            this.config = config;
            this.expiresAtMillis = expiresAtMillis;
        }

        private static Block timed(TranslationException failure, long expiresAtMillis) {
            return new Block(failure, false, null, expiresAtMillis);
        }

        private static Block configBound(TranslationException failure, ChatglotConfig config) {
            return new Block(failure, true, config, Long.MAX_VALUE);
        }
    }
}
