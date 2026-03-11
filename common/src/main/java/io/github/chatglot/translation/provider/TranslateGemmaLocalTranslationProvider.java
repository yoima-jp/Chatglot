package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.localbackend.LocalBackendManager;
import io.github.chatglot.localbackend.LocalBackendStatus;
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
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final LocalBackendManager backendManager;

    public TranslateGemmaLocalTranslationProvider(LocalBackendManager backendManager) {
        this.backendManager = backendManager;
    }

    @Override
    public String id() {
        return "translategemma_local";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        LocalBackendStatus status = backendManager.ensureBackendAvailable(config);
        if (!status.healthy()) {
            throw new TranslationException("Local backend unavailable: " + status.message());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", resolveModel(config));

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "You are a translation engine for Minecraft chat. Return only translated text.");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", TranslationPromptBuilder.buildStandardPrompt(request));
        messages.add(user);
        payload.add("messages", messages);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(status.backendUrl() + "/v1/chat/completions"))
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
                throw new TranslationException("Local backend returned empty translation.");
            }
            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Local backend request failed", e);
        }
    }

    private static String resolveModel(ChatglotConfig config) {
        if (config.localModelAlias != null && !config.localModelAlias.isBlank()) {
            return config.localModelAlias.trim();
        }
        return "translategemma";
    }

    private static String extractTranslatedText(JsonObject root) throws TranslationException {
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new TranslationException("Local backend returned no choices.");
        }

        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) {
            throw new TranslationException("Local backend returned an invalid response.");
        }
        JsonObject firstObject = first.getAsJsonObject();
        JsonObject message = firstObject.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new TranslationException("Local backend response missing message content.");
        }

        return message.get("content").getAsString().trim();
    }
}
