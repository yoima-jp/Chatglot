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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoogleTranslationProvider implements TranslationProvider {
    private static final String GOOGLE_TRANSLATE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";
    private static final String GOOGLE_TRANSLATE_ENABLE_URL_TEMPLATE =
        "https://console.developers.google.com/apis/api/translate.googleapis.com/overview?project=%s";
    private static final String GOOGLE_TRANSLATE_LIBRARY_URL =
        "https://console.cloud.google.com/apis/library/translate.googleapis.com";
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("project\\s+([0-9]+)", Pattern.CASE_INSENSITIVE);

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
                throw new TranslationException(buildFailureMessage(response.statusCode(), response.body()));
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

    private static String buildFailureMessage(int statusCode, String responseBody) {
        String abbreviated = TranslationPromptBuilder.abbreviate(responseBody, 500);
        if (statusCode != 403) {
            return "Google Translation request failed (" + statusCode + "): " + abbreviated;
        }

        String apiMessage = extractGoogleApiMessage(responseBody);
        String normalized = apiMessage.toLowerCase(Locale.ROOT);
        if (
            normalized.contains("cloud translation api has not been used in project") ||
            normalized.contains("it is disabled") ||
            normalized.contains("service disabled")
        ) {
            String projectId = extractProjectId(apiMessage);
            String enableUrl = projectId == null
                ? GOOGLE_TRANSLATE_LIBRARY_URL
                : String.format(Locale.ROOT, GOOGLE_TRANSLATE_ENABLE_URL_TEMPLATE, projectId);

            return "Google Translation request failed (403): Cloud Translation API is disabled or not enabled for this project. " +
            "Fix: 1) Enable API: " + enableUrl + " 2) Enable Billing for the same project " +
            "3) wait a few minutes and retry. Raw: " + abbreviated;
        }

        if (normalized.contains("billing")) {
            return "Google Translation request failed (403): Billing is not active for this project. " +
            "Enable Billing in Google Cloud Console, then retry. Raw: " + abbreviated;
        }

        return "Google Translation request failed (403): " + abbreviated;
    }

    private static String extractGoogleApiMessage(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString();
            }
        } catch (Exception ignored) {
            // Fall through and return raw body.
        }
        return responseBody == null ? "" : responseBody;
    }

    private static String extractProjectId(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = PROJECT_ID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private static String normalizeLanguageCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
