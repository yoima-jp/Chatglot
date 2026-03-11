package io.github.chatglot.localbackend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LocalBackendHealthChecker {
    private final HttpClient client = HttpClient.newHttpClient();

    public boolean isHealthy(String baseUrl, int timeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }

        int timeout = Math.max(2, timeoutSeconds);
        return callEndpoint(baseUrl, "/health", timeout) || callEndpoint(baseUrl, "/v1/models", timeout);
    }

    private boolean callEndpoint(String baseUrl, String endpoint, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
