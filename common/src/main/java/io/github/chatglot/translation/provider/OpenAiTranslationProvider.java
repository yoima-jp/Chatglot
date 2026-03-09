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

public final class OpenAiTranslationProvider implements TranslationProvider {
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String SYSTEM_PROMPT =
        "You are a translation engine for Minecraft chat messages. Return only translated text without explanations.";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.openaiApiKey == null || config.openaiApiKey.isBlank()) {
            throw new TranslationException("OpenAI API key is empty. Set it in Chatglot config.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", resolveModel(config.openaiModel));

        JsonArray input = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        JsonArray systemContent = new JsonArray();
        JsonObject systemText = new JsonObject();
        systemText.addProperty("type", "input_text");
        systemText.addProperty("text", SYSTEM_PROMPT);
        systemContent.add(systemText);
        systemMessage.add("content", systemContent);
        input.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        JsonArray userContent = new JsonArray();
        JsonObject userText = new JsonObject();
        userText.addProperty("type", "input_text");
        userText.addProperty("text", TranslationPromptBuilder.buildStandardPrompt(request));
        userContent.add(userText);
        userMessage.add("content", userContent);
        input.add(userMessage);

        payload.add("input", input);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(OPENAI_ENDPOINT))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Authorization", "Bearer " + config.openaiApiKey.trim())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "OpenAI request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String translated = extractTranslatedText(root);
            if (translated.isBlank()) {
                throw new TranslationException("OpenAI returned empty output.");
            }

            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("OpenAI request failed", e);
        }
    }

    private static String resolveModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) {
            return ChatglotConfig.OPENAI_DEFAULT_MODEL;
        }
        return configuredModel.trim();
    }

    private static String extractTranslatedText(JsonObject root) throws TranslationException {
        if (root.has("output_text") && root.get("output_text").isJsonPrimitive()) {
            return root.get("output_text").getAsString().trim();
        }

        JsonArray output = root.getAsJsonArray("output");
        if (output == null || output.isEmpty()) {
            throw new TranslationException("OpenAI returned no output.");
        }

        StringBuilder builder = new StringBuilder();
        for (JsonElement outputElement : output) {
            if (outputElement == null || !outputElement.isJsonObject()) {
                continue;
            }

            JsonObject outputObject = outputElement.getAsJsonObject();
            JsonArray content = outputObject.getAsJsonArray("content");
            if (content == null || content.isEmpty()) {
                continue;
            }

            for (JsonElement partElement : content) {
                if (partElement == null || !partElement.isJsonObject()) {
                    continue;
                }

                JsonObject part = partElement.getAsJsonObject();
                if (!part.has("text") || !part.get("text").isJsonPrimitive()) {
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
        }

        return builder.toString().trim();
    }
}
