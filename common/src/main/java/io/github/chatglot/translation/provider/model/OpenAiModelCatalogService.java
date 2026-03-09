package io.github.chatglot.translation.provider.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.ChatglotConstants;
import java.io.IOException;
import java.net.URI;
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

public final class OpenAiModelCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiModelCatalogService.class);
    private static final String MODELS_LIST_URL = "https://api.openai.com/v1/models";
    private static final String CACHE_FILENAME = "openai_models.json";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Path cacheFile;
    private List<String> cachedModels = List.of();

    public OpenAiModelCatalogService(Path configDir) {
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
            throw new IOException("OpenAI API key is empty");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(MODELS_LIST_URL))
            .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
            .header("Authorization", "Bearer " + apiKey.trim())
            .header("Content-Type", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException(
                "OpenAI model list request failed ("
                    + response.statusCode()
                    + "): "
                    + ApiModelCatalogSupport.abbreviate(response.body(), 500)
            );
        }

        List<String> models = parseModelsPayload(response.body());
        if (models.isEmpty()) {
            throw new IOException("OpenAI model API returned no models");
        }

        ApiModelCatalogSupport.writeCache(cacheFile, MODELS_LIST_URL, models);
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
        if (!object.has("data") || !object.get("data").isJsonArray()) {
            return List.of();
        }

        JsonArray data = object.getAsJsonArray("data");
        List<String> models = new ArrayList<>(data.size());
        for (JsonElement entry : data) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }

            JsonObject model = entry.getAsJsonObject();
            if (model.has("id") && model.get("id").isJsonPrimitive()) {
                models.add(model.get("id").getAsString());
            }
        }

        return ApiModelCatalogSupport.deduplicateAndNormalize(models);
    }
}
