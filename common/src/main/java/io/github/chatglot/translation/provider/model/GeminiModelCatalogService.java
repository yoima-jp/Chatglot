package io.github.chatglot.translation.provider.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.ChatglotConstants;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeminiModelCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiModelCatalogService.class);
    private static final String MODELS_LIST_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models?key=%s";
    private static final String CACHE_FILENAME = "gemini_models.json";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Path cacheFile;
    private List<String> cachedModels = List.of();

    public GeminiModelCatalogService(Path configDir) {
        this.cacheFile = configDir.resolve(ChatglotConstants.MOD_ID).resolve(CACHE_FILENAME);
        this.cachedModels = ApiModelCatalogSupport.readCachedModels(cacheFile, LOGGER);
    }

    public synchronized List<String> getCachedModels() {
        if (cachedModels.isEmpty()) {
            cachedModels = ApiModelCatalogSupport.readCachedModels(cacheFile, LOGGER);
        }
        return List.copyOf(cachedModels);
    }

    public synchronized List<String> refreshModels(String apiKey, int timeoutSeconds) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("Gemini API key is empty");
        }

        String endpoint = String.format(MODELS_LIST_URL_TEMPLATE, URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
            .header("Content-Type", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException(
                "Gemini model list request failed ("
                    + response.statusCode()
                    + "): "
                    + ApiModelCatalogSupport.abbreviate(response.body(), 500)
            );
        }

        List<String> models = parseModelsPayload(response.body());
        if (models.isEmpty()) {
            throw new IOException("Gemini model API returned no models");
        }

        ApiModelCatalogSupport.writeCache(cacheFile, "https://generativelanguage.googleapis.com/v1beta/models", models);
        cachedModels = models;
        return List.copyOf(cachedModels);
    }

    private static List<String> parseModelsPayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }

        JsonElement root = JsonParser.parseString(rawJson);
        if (!root.isJsonObject()) {
            return List.of();
        }

        JsonObject object = root.getAsJsonObject();
        if (!object.has("models") || !object.get("models").isJsonArray()) {
            return List.of();
        }

        JsonArray data = object.getAsJsonArray("models");
        List<String> models = new ArrayList<>(data.size());
        for (JsonElement entry : data) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }

            JsonObject model = entry.getAsJsonObject();
            if (model.has("supportedGenerationMethods") && model.get("supportedGenerationMethods").isJsonArray()) {
                JsonArray methods = model.getAsJsonArray("supportedGenerationMethods");
                boolean supportsGenerateContent = false;
                for (JsonElement method : methods) {
                    if (method != null && method.isJsonPrimitive() && "generateContent".equals(method.getAsString())) {
                        supportsGenerateContent = true;
                        break;
                    }
                }
                if (!supportsGenerateContent) {
                    continue;
                }
            }

            if (!model.has("name") || !model.get("name").isJsonPrimitive()) {
                continue;
            }

            String modelName = model.get("name").getAsString();
            if (modelName.startsWith("models/")) {
                modelName = modelName.substring("models/".length());
            }
            models.add(modelName);
        }

        return ApiModelCatalogSupport.deduplicateAndNormalize(models);
    }
}
