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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class AnthropicTranslationProvider implements TranslationProvider {
    private static final String ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_API_VERSION = "2023-06-01";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.anthropicApiKey == null || config.anthropicApiKey.isBlank()) {
            throw new TranslationException("Anthropic API key is empty. Set it in Chatglot config.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", resolveModel(config.anthropicModel));
        payload.addProperty("max_tokens", 1024);
        payload.addProperty("temperature", 0);
        payload.addProperty(
            "system",
            "You are a translation engine for Minecraft chat messages. Return only translated text without explanations."
        );

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", TranslationPromptBuilder.buildStandardPrompt(request));
        messages.add(userMessage);
        payload.add("messages", messages);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ANTHROPIC_ENDPOINT))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("x-api-key", config.anthropicApiKey.trim())
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "Anthropic request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String translated = extractTranslatedText(root);
            if (translated.isBlank()) {
                throw new TranslationException("Anthropic returned empty output.");
            }

            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Anthropic request failed", e);
        }
    }

    private static String resolveModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) {
            return ChatglotConfig.ANTHROPIC_DEFAULT_MODEL;
        }
        return configuredModel.trim();
    }

    private static String extractTranslatedText(JsonObject root) throws TranslationException {
        JsonArray content = root.getAsJsonArray("content");
        if (content == null || content.isEmpty()) {
            throw new TranslationException("Anthropic returned no content.");
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            JsonObject part = content.get(i).getAsJsonObject();
            String type = part.has("type") ? part.get("type").getAsString() : "";
            if (!"text".equals(type) || !part.has("text")) {
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
