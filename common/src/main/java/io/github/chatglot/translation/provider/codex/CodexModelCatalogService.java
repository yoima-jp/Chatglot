package io.github.chatglot.translation.provider.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.ChatglotConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexModelCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodexModelCatalogService.class);
    private static final String MODELS_LIST_URL = "https://modelapi.yoima.com/api/codex-models/list";
    private static final String CACHE_FILENAME = "codex_models.json";
    private static final long CURL_TIMEOUT_SECONDS = 12L;

    private final Path cacheFile;
    private List<String> cachedModels = List.of();

    public CodexModelCatalogService(Path configDir) {
        this.cacheFile = configDir.resolve(ChatglotConstants.MOD_ID).resolve(CACHE_FILENAME);
        this.cachedModels = readCachedModels();
    }

    public synchronized void initializeIfNeeded() {
        if (!cachedModels.isEmpty()) {
            return;
        }

        try {
            cachedModels = fetchAndSaveModels();
            LOGGER.info("Fetched and cached Codex model list on startup. count={}", cachedModels.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch Codex model list on startup: {}", e.getMessage());
        }
    }

    public synchronized List<String> getCachedModels() {
        if (cachedModels.isEmpty()) {
            cachedModels = readCachedModels();
        }
        return List.copyOf(cachedModels);
    }

    public synchronized List<String> refreshModels() throws IOException, InterruptedException {
        cachedModels = fetchAndSaveModels();
        return List.copyOf(cachedModels);
    }

    private List<String> fetchAndSaveModels() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("curl", "-sS", MODELS_LIST_URL).start();

        boolean finished = process.waitFor(CURL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("curl timeout while fetching Codex models");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("curl failed (exit=" + process.exitValue() + "): " + abbreviate(stderr, 300));
        }

        List<String> models = parseModelsPayload(stdout);
        if (models.isEmpty()) {
            throw new IOException("Codex model API returned no models");
        }

        writeCache(models);
        return models;
    }

    private List<String> readCachedModels() {
        if (!Files.exists(cacheFile)) {
            return List.of();
        }

        try {
            String raw = Files.readString(cacheFile, StandardCharsets.UTF_8);
            return parseModelsPayload(raw);
        } catch (Exception e) {
            LOGGER.warn("Failed to read Codex model cache: {} reason={}", cacheFile, e.getMessage());
            return List.of();
        }
    }

    private void writeCache(List<String> models) throws IOException {
        Files.createDirectories(cacheFile.getParent());

        JsonObject payload = new JsonObject();
        payload.addProperty("fetched_at_epoch_seconds", Instant.now().getEpochSecond());
        payload.addProperty("source", MODELS_LIST_URL);
        JsonArray array = new JsonArray();
        for (String model : models) {
            array.add(model);
        }
        payload.add("models", array);

        Files.writeString(cacheFile, payload.toString(), StandardCharsets.UTF_8);
    }

    private static List<String> parseModelsPayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }

        JsonElement root = JsonParser.parseString(rawJson);
        JsonArray modelsArray = null;
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("models") && object.get("models").isJsonArray()) {
                modelsArray = object.getAsJsonArray("models");
            }
        } else if (root.isJsonArray()) {
            modelsArray = root.getAsJsonArray();
        }

        if (modelsArray == null) {
            return List.of();
        }

        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (JsonElement element : modelsArray) {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }
            String model = element.getAsString();
            if (model != null) {
                String normalized = model.trim();
                if (!normalized.isBlank()) {
                    dedup.add(normalized);
                }
            }
        }

        return List.copyOf(new ArrayList<>(dedup));
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
