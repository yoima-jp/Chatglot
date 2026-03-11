package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class TranslateGemmaLocalTranslationProvider implements TranslationProvider {
    private static final String SYSTEM_PROMPT =
        "You are a translation engine for Minecraft chat messages. Return only translated text without explanations.";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "translategemma_local";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.translategemmaLocalModelPath == null || config.translategemmaLocalModelPath.isBlank()) {
            throw new TranslationException("Local model path is empty. Configure it in Local TranslateGemma settings.");
        }

        String baseUrl = resolveBaseUrl(config);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", resolveModelAlias(config));

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", SYSTEM_PROMPT);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", TranslationPromptBuilder.buildStandardPrompt(request));
        messages.add(user);

        payload.add("messages", messages);
        payload.addProperty("temperature", 0.1);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "Local backend request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String translated = extractTranslation(root);
            if (translated.isBlank()) {
                throw new TranslationException("Local backend returned empty output.");
            }
            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Failed to call local backend. Ensure backend is running and healthy.", e);
        }
    }

    private static String extractTranslation(JsonObject root) throws TranslationException {
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new TranslationException("Local backend returned no choices.");
        }

        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new TranslationException("Local backend response is missing message.content.");
        }

        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString().trim();
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement element : content.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject part = element.getAsJsonObject();
                if (part.has("text") && part.get("text").isJsonPrimitive()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(part.get("text").getAsString());
                }
            }
            return builder.toString().trim();
        }
        return "";
    }

    private static String resolveModelAlias(ChatglotConfig config) {
        if (config.translategemmaLocalModelAlias == null || config.translategemmaLocalModelAlias.isBlank()) {
            return "translategemma-local";
        }
        return config.translategemmaLocalModelAlias.trim();
    }

    private static String resolveBaseUrl(ChatglotConfig config) throws TranslationException {
        String configured = config.translategemmaLocalBackendUrl;
        if (configured == null || configured.isBlank()) {
            configured = "http://127.0.0.1:" + config.translategemmaLocalBackendPort;
        }

        String normalized = configured.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new TranslationException("Local backend URL must start with http:// or https://");
        }
        return normalized;
    }
}
