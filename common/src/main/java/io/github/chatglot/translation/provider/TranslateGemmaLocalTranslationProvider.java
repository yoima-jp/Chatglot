package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.ChatglotRuntime;
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
        ChatglotRuntime.get().localBackendManager().ensureBackendReadyOrThrow(config);
        if (config.translategemmaLocalModelPath == null || config.translategemmaLocalModelPath.isBlank()) {
            throw new TranslationException("Model path is empty. Set Local TranslateGemma model path in settings.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", resolveModel(config.translategemmaLocalModelName));

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", SYSTEM_PROMPT);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", TranslationPromptBuilder.buildStandardPrompt(request));
        messages.add(userMessage);
        payload.add("messages", messages);
        payload.addProperty("temperature", 0.1);

        String endpoint = resolveBackendUrl(config) + "/v1/chat/completions";
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
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
            String translated = extractTranslatedText(root);
            if (translated.isBlank()) {
                throw new TranslationException("Local backend returned empty output.");
            }
            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Local backend request failed", e);
        }
    }

    private static String resolveBackendUrl(ChatglotConfig config) {
        if (config.translategemmaLocalBackendUrl == null || config.translategemmaLocalBackendUrl.isBlank()) {
            return "http://127.0.0.1:" + config.translategemmaLocalPort;
        }
        return config.translategemmaLocalBackendUrl.trim();
    }

    private static String resolveModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) {
            return ChatglotConfig.TRANSLATEGEMMA_LOCAL_DEFAULT_MODEL_NAME;
        }
        return configuredModel.trim();
    }

    private static String extractTranslatedText(JsonObject root) {
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return "";
        }

        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            return "";
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
                if (!part.has("text") || !part.get("text").isJsonPrimitive()) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(part.get("text").getAsString());
            }
            return builder.toString().trim();
        }

        return "";
    }
}
