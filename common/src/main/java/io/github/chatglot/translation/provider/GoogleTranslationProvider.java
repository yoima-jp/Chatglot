package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

public final class GoogleTranslationProvider implements TranslationProvider {
    private static final String GOOGLE_TRANSLATE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    @Override
    public String id() {
        return "google";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.googleTranslateApiKey == null || config.googleTranslateApiKey.isBlank()) {
            throw ProviderHttp.configMissing(id(), "Google Translation API key is empty. Set it in Chatglot config.");
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
            HttpResponse<String> response = ProviderHttp.send(httpRequest, id(), "Google Translation");
            ProviderHttp.requireSuccess(response, id(), "Google Translation");

            JsonObject root = ProviderHttp.parseJson(response.body(), id(), "Google Translation").getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            if (data == null) {
                throw ProviderHttp.badResponse(id(), "Google Translation response has no data.", response.body());
            }

            JsonArray translations = data.getAsJsonArray("translations");
            if (translations == null || translations.isEmpty()) {
                throw ProviderHttp.badResponse(id(), "Google Translation returned no translations.", response.body());
            }

            JsonObject first = translations.get(0).getAsJsonObject();
            String translated = first.has("translatedText") ? first.get("translatedText").getAsString() : "";
            if (translated.isBlank()) {
                throw ProviderHttp.emptyOutput(id(), "Google Translation returned empty output.");
            }

            String detected = first.has("detectedSourceLanguage")
                ? first.get("detectedSourceLanguage").getAsString()
                : request.sourceLanguageHint();

            return new TranslationResult(translated, detected, id());
        } catch (TranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ProviderHttp.badResponse(id(), "Google Translation returned an invalid response.", e.getMessage());
        }
    }

    private static String normalizeLanguageCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
