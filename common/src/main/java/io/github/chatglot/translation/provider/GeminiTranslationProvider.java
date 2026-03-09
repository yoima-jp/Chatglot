package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class GeminiTranslationProvider implements TranslationProvider {
    private static final String GEMINI_ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.geminiApiKey == null || config.geminiApiKey.isBlank()) {
            throw new TranslationException("Gemini API key is empty. Set it in Chatglot config.");
        }

        String endpoint = String.format(
            GEMINI_ENDPOINT_TEMPLATE,
            URLEncoder.encode(resolveModel(config.geminiModel), StandardCharsets.UTF_8),
            URLEncoder.encode(config.geminiApiKey.trim(), StandardCharsets.UTF_8)
        );

        JsonObject payload = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", TranslationPromptBuilder.buildStandardPrompt(request));
        parts.add(textPart);
        content.add("parts", parts);
        contents.add(content);
        payload.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0);
        payload.add("generationConfig", generationConfig);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "Gemini request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String translated = extractTranslatedText(root);
            if (translated.isBlank()) {
                throw new TranslationException("Gemini returned empty output.");
            }

            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Gemini request failed", e);
        }
    }

    private static String resolveModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) {
            return ChatglotConfig.GEMINI_DEFAULT_MODEL;
        }

        String normalized = configuredModel.trim();
        if (normalized.startsWith("models/")) {
            return normalized.substring("models/".length());
        }
        return normalized;
    }

    private static String extractTranslatedText(JsonObject root) throws TranslationException {
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            if (root.has("promptFeedback") && root.get("promptFeedback").isJsonObject()) {
                JsonObject feedback = root.getAsJsonObject("promptFeedback");
                if (feedback.has("blockReason")) {
                    throw new TranslationException("Gemini blocked the prompt: " + feedback.get("blockReason").getAsString());
                }
            }
            throw new TranslationException("Gemini returned no candidates.");
        }

        JsonObject first = candidates.get(0).getAsJsonObject();
        JsonObject content = first.getAsJsonObject("content");
        if (content == null) {
            throw new TranslationException("Gemini response has no content.");
        }

        JsonArray parts = content.getAsJsonArray("parts");
        if (parts == null || parts.isEmpty()) {
            throw new TranslationException("Gemini response has no text parts.");
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            JsonObject part = parts.get(i).getAsJsonObject();
            if (!part.has("text")) {
                continue;
            }
            String text = part.get("text").getAsString();
            if (!text.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString().trim();
    }
}
