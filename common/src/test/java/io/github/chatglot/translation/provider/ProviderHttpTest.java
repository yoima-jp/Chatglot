package io.github.chatglot.translation.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.translation.TranslationException.Kind;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderHttpTest {
    @Test
    void mapsCommonStatusesAndProviderSpecificQuotaResponses() {
        assertEquals(Kind.AUTH, ProviderHttp.statusKind(401));
        assertEquals(Kind.AUTH, ProviderHttp.statusKind(403));
        assertEquals(Kind.RATE_LIMITED, ProviderHttp.statusKind(429));
        assertEquals(Kind.TIMEOUT, ProviderHttp.statusKind(408));
        assertEquals(Kind.TIMEOUT, ProviderHttp.statusKind(504));
        assertEquals(Kind.SERVER, ProviderHttp.statusKind(503));
        assertEquals(Kind.BAD_RESPONSE, ProviderHttp.statusKind(404));
        assertEquals(Kind.QUOTA, ProviderHttp.statusKind("deepl", 456, ""));
        assertEquals(Kind.QUOTA, ProviderHttp.statusKind("gemini", 429, "RESOURCE_EXHAUSTED"));
        assertEquals(Kind.QUOTA, ProviderHttp.statusKind("google", 429, "quota exceeded"));
        assertEquals(Kind.QUOTA, ProviderHttp.statusKind("azure", 403, "monthly quota exceeded"));
    }

    @Test
    void parsesRetryAfterAsSecondsAndHttpDate() {
        Instant now = Instant.parse("2025-04-01T10:00:00Z");

        assertEquals(Duration.ofSeconds(12), ProviderHttp.parseRetryAfter("12", now).orElseThrow());
        assertEquals(
            Duration.ofSeconds(37),
            ProviderHttp.parseRetryAfter("Tue, 01 Apr 2025 10:00:37 GMT", now).orElseThrow()
        );
        assertEquals(Duration.ZERO, ProviderHttp.parseRetryAfter("Mon, 31 Mar 2025 10:00:00 GMT", now).orElseThrow());
        assertTrue(ProviderHttp.parseRetryAfter("not a date", now).isEmpty());
    }

    @Test
    void masksCredentialsAndBoundsDiagnosticText() {
        String source = "Authorization: Bearer bearer-secret, key=key-secret sk-1234567890";
        String masked = ProviderHttp.maskAndLimit(source);

        assertFalse(masked.contains("bearer-secret"));
        assertFalse(masked.contains("key-secret"));
        assertFalse(masked.contains("sk-1234567890"));
        assertTrue(ProviderHttp.maskAndLimit("x".repeat(600)).length() <= 503);
    }
}
