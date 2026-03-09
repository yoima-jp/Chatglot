package io.github.chatglot.translation.provider.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;

final class ApiModelCatalogSupport {
    private ApiModelCatalogSupport() {
    }

    static List<String> readCachedModels(Path cacheFile, Logger logger) {
        if (!Files.exists(cacheFile)) {
            return List.of();
        }

        try {
            String raw = Files.readString(cacheFile, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonObject()) {
                return List.of();
            }

            JsonObject object = root.getAsJsonObject();
            if (!object.has("models") || !object.get("models").isJsonArray()) {
                return List.of();
            }

            JsonArray modelsArray = object.getAsJsonArray("models");
            List<String> models = new ArrayList<>(modelsArray.size());
            for (JsonElement element : modelsArray) {
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    models.add(element.getAsString());
                }
            }
            return deduplicateAndNormalize(models);
        } catch (Exception e) {
            logger.warn("Failed to read model cache: {} reason={}", cacheFile, e.getMessage());
            return List.of();
        }
    }

    static void writeCache(Path cacheFile, String source, List<String> models) throws IOException {
        Files.createDirectories(cacheFile.getParent());

        JsonObject payload = new JsonObject();
        payload.addProperty("fetched_at_epoch_seconds", Instant.now().getEpochSecond());
        payload.addProperty("source", source);

        JsonArray array = new JsonArray();
        for (String model : models) {
            array.add(model);
        }
        payload.add("models", array);

        Files.writeString(cacheFile, payload.toString(), StandardCharsets.UTF_8);
    }

    static List<String> deduplicateAndNormalize(Iterable<String> candidates) {
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            String normalized = candidate.trim();
            if (!normalized.isBlank()) {
                dedup.add(normalized);
            }
        }
        return List.copyOf(new ArrayList<>(dedup));
    }

    static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
