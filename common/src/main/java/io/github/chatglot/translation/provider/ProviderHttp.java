package io.github.chatglot.translation.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationException.Kind;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderHttp {
    private static final int MAX_DETAIL_LENGTH = 500;
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)(\\bBearer\\s+)[^\\s,;\\\"]+");
    private static final Pattern DEEPL_KEY_SECRET = Pattern.compile("(?i)(DeepL-Auth-Key\\s+)[^\\s,;]+");
    private static final Pattern NAMED_KEY_SECRET = Pattern.compile("(?i)([\\x22]?(?:api[_-]?key|key|token|access_token)[\\x22]?\\s*[:=]\\s*[\\x22]?)[^\\s,;\\x22}]+([\\x22]?)");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern GOOGLE_KEY = Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}\\b");

    // HttpClient.newHttpClient() defaults to NEVER; preserving it avoids changing existing redirects.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private ProviderHttp() { }

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpResponse<String> send(HttpRequest request, String providerId, String displayName)
        throws TranslationException {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw TranslationException.caused(Kind.TIMEOUT, displayName + " connection timed out.", providerId, e)
                .asConnectionTimeout();
        } catch (HttpTimeoutException e) {
            throw TranslationException.caused(Kind.TIMEOUT, displayName + " request timed out.", providerId, e);
        } catch (ConnectException | UnknownHostException e) {
            throw TranslationException.caused(Kind.NETWORK, displayName + " could not connect.", providerId, e);
        } catch (IOException e) {
            throw TranslationException.caused(Kind.NETWORK, displayName + " request failed.", providerId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw TranslationException.caused(Kind.UNKNOWN, displayName + " request was interrupted.", providerId, e);
        }
    }

    public static void requireSuccess(HttpResponse<String> response, String providerId, String displayName)
        throws TranslationException {
        int status = response.statusCode();
        if (status < 400) {
            return;
        }
        throw httpFailure(response, providerId, displayName);
    }

    public static TranslationException httpFailure(HttpResponse<String> response, String providerId, String displayName) {
        int status = response.statusCode();
        String body = response.body();
        Kind kind = statusKind(providerId, status, body);
        String detail = maskAndLimit(body);
        return TranslationException.classified(
            kind,
            displayName + " request failed (HTTP " + status + ").",
            providerId,
            status,
            parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null)).orElse(null),
            detail,
            null
        );
    }

    public static Kind statusKind(int status) {
        if (status == 401 || status == 403) {
            return Kind.AUTH;
        }
        if (status == 429) {
            return Kind.RATE_LIMITED;
        }
        if (status == 408 || status == 504) {
            return Kind.TIMEOUT;
        }
        if (status >= 500 && status <= 599) {
            return Kind.SERVER;
        }
        if (status >= 400 && status <= 499) {
            return Kind.BAD_RESPONSE;
        }
        return Kind.UNKNOWN;
    }

    public static Kind statusKind(String providerId, int status, String body) {
        String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if ("deepl".equals(providerId) && status == 456) {
            return Kind.QUOTA;
        }
        if ("azure".equals(providerId) && status == 403 && containsQuotaSignal(normalized)) {
            return Kind.QUOTA;
        }
        if (("gemini".equals(providerId) || "google".equals(providerId)) && status == 429
            && containsQuotaSignal(normalized)) {
            return Kind.QUOTA;
        }
        if (("openai".equals(providerId) || "custom_llm".equals(providerId)) && status == 429
            && containsQuotaSignal(normalized)) {
            return Kind.QUOTA;
        }
        return statusKind(status);
    }

    private static boolean containsQuotaSignal(String body) {
        return body.contains("resource_exhausted") || body.contains("insufficient_quota") || body.contains("quota");
    }

    public static Optional<Duration> parseRetryAfter(String value) {
        return parseRetryAfter(value, Instant.now());
    }

    public static Optional<Duration> parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (NumberFormatException ignored) {
            // Retry-After also permits an HTTP-date.
        } catch (ArithmeticException ignored) {
            return Optional.empty();
        }
        try {
            Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Optional.of(Duration.between(now, retryAt).isNegative() ? Duration.ZERO : Duration.between(now, retryAt));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static JsonElement parseJson(String body, String providerId, String displayName)
        throws TranslationException {
        try {
            if (body == null || body.isBlank()) {
                throw TranslationException.classified(
                    Kind.EMPTY_OUTPUT, displayName + " returned an empty response.", providerId, null, null, null, null
                );
            }
            return JsonParser.parseString(body);
        } catch (TranslationException e) {
            throw e;
        } catch (JsonParseException | IllegalStateException e) {
            throw TranslationException.classified(
                Kind.BAD_RESPONSE,
                displayName + " returned an invalid JSON response.",
                providerId,
                null,
                null,
                maskAndLimit(body),
                e
            );
        }
    }

    public static TranslationException configMissing(String providerId, String message) {
        return TranslationException.classified(Kind.CONFIG_MISSING, message, providerId);
    }

    public static TranslationException badResponse(String providerId, String message, String detail) {
        return TranslationException.classified(Kind.BAD_RESPONSE, message, providerId, null, null, maskAndLimit(detail), null);
    }

    public static TranslationException emptyOutput(String providerId, String message) {
        return TranslationException.classified(Kind.EMPTY_OUTPUT, message, providerId);
    }

    public static String maskAndLimit(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // Providers sometimes echo request credentials, so diagnostics are redacted and bounded before storage.
        String masked = replace(BEARER_SECRET, value, "$1[REDACTED]");
        masked = replace(DEEPL_KEY_SECRET, masked, "$1[REDACTED]");
        masked = replace(NAMED_KEY_SECRET, masked, "$1[REDACTED]$2");
        masked = replace(OPENAI_KEY, masked, "[REDACTED]");
        masked = replace(GOOGLE_KEY, masked, "[REDACTED]");
        return masked.length() <= MAX_DETAIL_LENGTH ? masked : masked.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    private static String replace(Pattern pattern, String value, String replacement) {
        Matcher matcher = pattern.matcher(value);
        return matcher.replaceAll(replacement);
    }
}

