package io.github.chatglot.localbackend;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalBackendHealthChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/HealthCheck");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile String lastError = "";

    public boolean isHealthy(String baseUrl, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        Set<String> names = fetchModelNames(baseUrl, timeout);
        return names != null;
    }

    public boolean hasModel(String baseUrl, String modelName, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        Set<String> names = fetchModelNames(baseUrl, timeout);
        if (names == null || names.isEmpty()) {
            return false;
        }

        String normalizedTarget = normalizeModelName(modelName);
        for (String candidate : names) {
            if (normalizedTarget.equals(normalizeModelName(candidate))) {
                return true;
            }
        }
        return false;
    }

    public String getLastError() {
        return lastError;
    }

    private Set<String> fetchModelNames(String baseUrl, Duration timeout) {
        Set<String> names = fetchTags(baseUrl + "/api/tags", timeout);
        if (names != null) {
            return names;
        }
        return fetchOpenAiModels(baseUrl + "/v1/models", timeout);
    }

    private Set<String> fetchTags(String endpoint, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastError = "GET " + endpoint + " returned HTTP " + response.statusCode();
                LOGGER.warn("Health check failed: {}", lastError);
                return null;
            }

            lastError = "";
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray models = root.getAsJsonArray("models");
            if (models == null) {
                return Set.of();
            }

            Set<String> names = new HashSet<>();
            for (JsonElement element : models) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                if (model.has("name") && model.get("name").isJsonPrimitive()) {
                    names.add(model.get("name").getAsString());
                }
            }
            return names;
        } catch (HttpConnectTimeoutException e) {
            lastError = "Connection timed out: " + endpoint;
            LOGGER.warn("Health check failed: {}", lastError);
            return null;
        } catch (ConnectException e) {
            lastError = "Connection refused: " + endpoint;
            LOGGER.warn("Health check failed: {}", lastError);
            return null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage() + " (endpoint: " + endpoint + ")";
            LOGGER.warn("Health check failed: {}", lastError, e);
            return null;
        }
    }

    private Set<String> fetchOpenAiModels(String endpoint, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastError = "GET " + endpoint + " returned HTTP " + response.statusCode();
                LOGGER.warn("Health check failed: {}", lastError);
                return null;
            }

            lastError = "";
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray models = root.getAsJsonArray("data");
            if (models == null) {
                return Set.of();
            }

            Set<String> names = new HashSet<>();
            for (JsonElement element : models) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                if (model.has("id") && model.get("id").isJsonPrimitive()) {
                    names.add(model.get("id").getAsString());
                }
            }
            return names;
        } catch (HttpConnectTimeoutException e) {
            lastError = "Connection timed out: " + endpoint;
            LOGGER.warn("Health check failed: {}", lastError);
            return null;
        } catch (ConnectException e) {
            lastError = "Connection refused: " + endpoint;
            LOGGER.warn("Health check failed: {}", lastError);
            return null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage() + " (endpoint: " + endpoint + ")";
            LOGGER.warn("Health check failed: {}", lastError, e);
            return null;
        }
    }

    private static String normalizeModelName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value.trim().toLowerCase();
        if (!normalized.contains(":")) {
            return normalized + ":latest";
        }
        return normalized;
    }
}
