package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
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
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "translategemma_local";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.localBackendModel == null || config.localBackendModel.isBlank()) {
            throw new TranslationException("Local backend model alias is empty. Set Local TranslateGemma model name in config.");
        }

        try {
            ChatglotRuntime.get().localBackendManager().ensureBackendAvailable(config);
        } catch (Exception e) {
            throw new TranslationException("Local backend is unavailable: " + e.getMessage(), e);
        }

        String baseUrl = resolveBaseUrl(config);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.localBackendModel.trim());
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty(
            "content",
            "You are a translation engine for Minecraft chat messages. Return only translated text without explanations."
        );
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", TranslationPromptBuilder.buildStandardPrompt(request));
        messages.add(user);

        payload.add("messages", messages);
        payload.addProperty("temperature", 0.0);

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
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new TranslationException("Local backend returned no choices.");
            }

            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = first.getAsJsonObject("message");
            if (message == null || !message.has("content")) {
                throw new TranslationException("Local backend response is missing translated content.");
            }

            String translated = message.get("content").getAsString().trim();
            if (translated.isBlank()) {
                throw new TranslationException("Local backend returned empty translation output.");
            }
            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Local backend request failed", e);
        }
    }

    private static String resolveBaseUrl(ChatglotConfig config) {
        String url = config.localBackendUrl;
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:" + config.localBackendPort;
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
