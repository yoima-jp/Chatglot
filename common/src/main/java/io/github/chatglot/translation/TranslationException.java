package io.github.chatglot.translation;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

public final class TranslationException extends Exception {
    public enum Kind {
        CONFIG_MISSING,
        AUTH,
        RATE_LIMITED,
        QUOTA,
        TIMEOUT,
        NETWORK,
        SERVER,
        BAD_RESPONSE,
        EMPTY_OUTPUT,
        DISABLED,
        UNKNOWN
    }

    private final Kind kind;
    private final String providerId;
    private final Integer httpStatus;
    private final Duration retryAfter;
    private final String detail;
    private final boolean connectionTimeout;
    private final boolean suppressedByBreaker;

    // Legacy call sites keep UNKNOWN so classification is only claimed where the failure source is known.
    public TranslationException(String message) {
        this(message, null);
    }

    public TranslationException(String message, Throwable cause) {
        this(message, cause, Kind.UNKNOWN, null, null, null, false, false);
    }

    private TranslationException(
        String message,
        Throwable cause,
        Kind kind,
        String providerId,
        Integer httpStatus,
        Duration retryAfter,
        boolean connectionTimeout,
        boolean suppressedByBreaker
    ) {
        this(message, cause, kind, providerId, httpStatus, retryAfter, null, connectionTimeout, suppressedByBreaker);
    }

    private TranslationException(
        String message,
        Throwable cause,
        Kind kind,
        String providerId,
        Integer httpStatus,
        Duration retryAfter,
        String detail,
        boolean connectionTimeout,
        boolean suppressedByBreaker
    ) {
        super(message, cause);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.providerId = providerId;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
        this.detail = detail;
        this.connectionTimeout = connectionTimeout;
        this.suppressedByBreaker = suppressedByBreaker;
    }

    public static TranslationException classified(
        Kind kind,
        String message,
        String providerId,
        Integer httpStatus,
        Duration retryAfter,
        String detail,
        Throwable cause
    ) {
        return new TranslationException(message, cause, kind, providerId, httpStatus, retryAfter, detail, false, false);
    }

    public static TranslationException classified(Kind kind, String message, String providerId) {
        return classified(kind, message, providerId, null, null, null, null);
    }

    public static TranslationException caused(Kind kind, String message, String providerId, Throwable cause) {
        return classified(kind, message, providerId, null, null, null, cause);
    }

    public Kind kind() {
        return kind;
    }

    public String providerId() {
        return providerId;
    }

    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public String detail() {
        return detail;
    }

    public boolean connectionTimeout() {
        return connectionTimeout;
    }

    public boolean suppressedByBreaker() {
        return suppressedByBreaker;
    }

    public TranslationException asConnectionTimeout() {
        return new TranslationException(
            getMessage(), getCause(), kind, providerId, httpStatus, retryAfter, detail, true, suppressedByBreaker
        );
    }

    public TranslationException asSuppressedByBreaker() {
        return new TranslationException(
            getMessage(), getCause(), kind, providerId, httpStatus, retryAfter, detail, connectionTimeout, true
        );
    }

    public TranslationException withProviderId(String value) {
        return new TranslationException(
            getMessage(), getCause(), kind, value, httpStatus, retryAfter, detail, connectionTimeout, suppressedByBreaker
        );
    }
}
