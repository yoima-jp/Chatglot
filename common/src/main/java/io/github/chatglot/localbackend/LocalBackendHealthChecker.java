package io.github.chatglot.localbackend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LocalBackendHealthChecker {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public boolean isHealthy(String baseUrl, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        return checkEndpoint(baseUrl + "/health", timeout) || checkEndpoint(baseUrl + "/v1/models", timeout);
    }

    private boolean checkEndpoint(String endpoint, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }
}
