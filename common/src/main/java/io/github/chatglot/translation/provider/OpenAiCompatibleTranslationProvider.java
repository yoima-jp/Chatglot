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
import java.util.Locale;

public final class OpenAiCompatibleTranslationProvider implements TranslationProvider {
    private static final String SYSTEM_PROMPT =
        "You are a translation engine for Minecraft chat messages. Return only translated text without explanations.";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "custom_llm";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        String model = requireModel(config.openaiCompatibleModel);
        boolean chatCompletions = ChatglotConfig.OPENAI_COMPATIBLE_PROTOCOL_CHAT_COMPLETIONS.equals(
            config.openaiCompatibleProtocol
        );
        URI endpoint = resolveEndpoint(
            config.openaiCompatibleBaseUrl,
            chatCompletions ? "chat/completions" : "responses"
        );
        JsonObject payload = chatCompletions
            ? buildChatCompletionsPayload(request, model)
            : buildOpenResponsesPayload(request, model);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (config.openaiCompatibleApiKey != null && !config.openaiCompatibleApiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + config.openaiCompatibleApiKey.trim());
        }

        try {
            HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() >= 400) {
                throw createHttpException(response.statusCode(), response.body());
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String translated = chatCompletions ? extractChatCompletionText(root) : extractOpenResponsesText(root);
            if (translated.isBlank()) {
                throw new TranslationException("The compatible API returned empty output.");
            }
            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new TranslationException("The compatible API returned an unsupported response format.", e);
        } catch (Exception e) {
            throw new TranslationException("The compatible API request failed.", e);
        }
    }

    private static JsonObject buildOpenResponsesPayload(TranslationRequest request, String model) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("instructions", SYSTEM_PROMPT);
        payload.addProperty("input", TranslationPromptBuilder.buildStandardPrompt(request));
        return payload;
    }

    private static JsonObject buildChatCompletionsPayload(TranslationRequest request, String model) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", TranslationPromptBuilder.buildStandardPrompt(request)));
        payload.add("messages", messages);
        return payload;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static URI resolveEndpoint(String configuredBaseUrl, String endpointPath) throws TranslationException {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new TranslationException("Compatible API Base URL is empty.");
        }
        try {
            URI baseUri = URI.create(configuredBaseUrl.trim());
            String scheme = baseUri.getScheme() == null ? "" : baseUri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || baseUri.getHost() == null) {
                throw new TranslationException("Compatible API Base URL must be an HTTP or HTTPS URL.");
            }
            if (baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null) {
                throw new TranslationException("Compatible API Base URL must not contain credentials, a query, or a fragment.");
            }
            String normalizedBase = configuredBaseUrl.trim().replaceAll("/+$", "");
            return URI.create(normalizedBase + "/" + endpointPath);
        } catch (TranslationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new TranslationException("Compatible API Base URL is invalid.", e);
        }
    }

    private static String requireModel(String configuredModel) throws TranslationException {
        if (configuredModel == null || configuredModel.isBlank()) {
            throw new TranslationException("Compatible API model is empty.");
        }
        return configuredModel.trim();
    }

    private static TranslationException createHttpException(int statusCode, String body) {
        String guidance = switch (statusCode) {
            case 401, 403 -> " Check the API key.";
            case 404 -> " Check the Base URL and selected API format.";
            case 429 -> " The server rate limit was reached.";
            default -> "";
        };
        return new TranslationException(
            "Compatible API request failed (" + statusCode + ")." + guidance + " "
                + TranslationPromptBuilder.abbreviate(body, 500)
        );
    }

    private static String extractChatCompletionText(JsonObject root) throws TranslationException {
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new TranslationException("The compatible API returned no choices.");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) {
            throw new TranslationException("The compatible API returned no message.");
        }
        return extractContentText(message.get("content"));
    }

    private static String extractOpenResponsesText(JsonObject root) throws TranslationException {
        if (root.has("output_text") && root.get("output_text").isJsonPrimitive()) {
            return root.get("output_text").getAsString().trim();
        }
        JsonArray output = root.getAsJsonArray("output");
        if (output == null) {
            throw new TranslationException("The compatible API returned no output.");
        }
        StringBuilder result = new StringBuilder();
        for (JsonElement itemElement : output) {
            if (!itemElement.isJsonObject()) {
                continue;
            }
            JsonArray content = itemElement.getAsJsonObject().getAsJsonArray("content");
            if (content == null) {
                continue;
            }
            appendContentParts(result, content);
        }
        return result.toString().trim();
    }

    private static String extractContentText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString().trim();
        }
        if (content.isJsonArray()) {
            StringBuilder result = new StringBuilder();
            appendContentParts(result, content.getAsJsonArray());
            return result.toString().trim();
        }
        return "";
    }

    private static void appendContentParts(StringBuilder result, JsonArray content) {
        for (JsonElement partElement : content) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            JsonElement text = part.get("text");
            if (text != null && text.isJsonPrimitive() && !text.getAsString().isBlank()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(text.getAsString());
            }
        }
    }
}
