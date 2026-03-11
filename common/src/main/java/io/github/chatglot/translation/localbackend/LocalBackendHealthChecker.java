package io.github.chatglot.translation.localbackend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LocalBackendHealthChecker {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public boolean isHealthy(String backendUrl, int timeoutSeconds) {
        return ping(backendUrl + "/health", timeoutSeconds) || ping(backendUrl + "/v1/models", timeoutSeconds);
    }

    private boolean ping(String url, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }
}
