package io.github.chatglot.localbackend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.translation.TranslationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LocalBackendHealthChecker {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public boolean isHealthy(String baseUrl, int timeoutSeconds) throws TranslationException {
        String normalizedBase = normalizeBase(baseUrl);
        if (pingHealthEndpoint(normalizedBase, timeoutSeconds)) {
            return true;
        }
        return pingModelsEndpoint(normalizedBase, timeoutSeconds);
    }

    private boolean pingHealthEndpoint(String normalizedBase, int timeoutSeconds) throws TranslationException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedBase + "/health"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 400) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean pingModelsEndpoint(String normalizedBase, int timeoutSeconds) throws TranslationException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedBase + "/v1/models"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return false;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.has("data");
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeBase(String configured) throws TranslationException {
        if (configured == null || configured.isBlank()) {
            throw new TranslationException("Local backend URL is empty.");
        }
        String value = configured.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
