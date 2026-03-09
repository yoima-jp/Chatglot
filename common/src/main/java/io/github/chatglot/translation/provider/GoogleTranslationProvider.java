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
import java.util.Locale;

public final class GoogleTranslationProvider implements TranslationProvider {
    private static final String GOOGLE_TRANSLATE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "google";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.googleTranslateApiKey == null || config.googleTranslateApiKey.isBlank()) {
            throw new TranslationException("Google Translation API key is empty. Set it in Chatglot config.");
        }

        String endpoint = GOOGLE_TRANSLATE_ENDPOINT + "?key=" + URLEncoder.encode(config.googleTranslateApiKey.trim(), StandardCharsets.UTF_8);

        JsonObject payload = new JsonObject();
        payload.addProperty("q", request.text());
        payload.addProperty("target", normalizeLanguageCode(request.targetLanguage()));
        payload.addProperty("format", "text");
        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            payload.addProperty("source", normalizeLanguageCode(request.sourceLanguageHint()));
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "Google Translation request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            if (data == null) {
                throw new TranslationException("Google Translation response has no data.");
            }

            JsonArray translations = data.getAsJsonArray("translations");
            if (translations == null || translations.isEmpty()) {
                throw new TranslationException("Google Translation returned no translations.");
            }

            JsonObject first = translations.get(0).getAsJsonObject();
            String translated = first.has("translatedText") ? first.get("translatedText").getAsString() : "";
            if (translated.isBlank()) {
                throw new TranslationException("Google Translation returned empty output.");
            }

            String detected = first.has("detectedSourceLanguage")
                ? first.get("detectedSourceLanguage").getAsString()
                : request.sourceLanguageHint();

            return new TranslationResult(translated, detected, id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Google Translation request failed", e);
        }
    }

    private static String normalizeLanguageCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
