package io.github.chatglot.translation.provider;

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

public final class GasTranslationProvider implements TranslationProvider {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "gas";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.gasWebAppUrl == null || config.gasWebAppUrl.isBlank()) {
            throw new TranslationException("GAS Web App URL is empty. Set it in Chatglot config.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("text", request.text());
        payload.addProperty("target", normalizeLanguageCode(request.targetLanguage()));
        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            payload.addProperty("source", normalizeLanguageCode(request.sourceLanguageHint()));
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(config.gasWebAppUrl.trim()))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "GAS translation request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.has("ok") && !root.get("ok").getAsBoolean()) {
                throw new TranslationException("GAS translation failed: " + extractErrorMessage(root));
            }

            String translated = getString(root, "translatedText");
            if (translated.isBlank()) {
                throw new TranslationException("GAS translation returned empty output.");
            }

            String detected = getString(root, "source");
            if (detected.isBlank() || detected.equalsIgnoreCase("auto")) {
                detected = request.sourceLanguageHint();
            }

            return new TranslationResult(translated, detected, id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("GAS translation request failed", e);
        }
    }

    private static String extractErrorMessage(JsonObject root) {
        String message = getString(root, "message");
        String details = getString(root, "details");
        String error = getString(root, "error");

        StringBuilder builder = new StringBuilder();
        if (!error.isBlank()) {
            builder.append(error);
        }
        if (!message.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" - ");
            }
            builder.append(message);
        }
        if (!details.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" - ");
            }
            builder.append(details);
        }

        if (builder.length() == 0) {
            return "Unknown GAS error response.";
        }
        return builder.toString();
    }

    private static String getString(JsonObject root, String key) {
        if (root == null || key == null || key.isBlank() || !root.has(key) || root.get(key).isJsonNull()) {
            return "";
        }
        return root.get(key).getAsString().trim();
    }

    private static String normalizeLanguageCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
