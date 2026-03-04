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

public final class DeepLTranslationProvider implements TranslationProvider {
    private static final String DEEPL_FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate";
    private static final String DEEPL_PRO_ENDPOINT = "https://api.deepl.com/v2/translate";
    private static final String AUTH_HEADER_PREFIX = "DeepL-Auth-Key ";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String id() {
        return "deepl";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.deeplApiKey == null || config.deeplApiKey.isBlank()) {
            throw new TranslationException("DeepL API key is empty. Set it in Chatglot config.");
        }

        StringBuilder body = new StringBuilder();
        appendForm(body, "text", request.text());
        appendForm(body, "target_lang", request.targetLanguage());
        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            appendForm(body, "source_lang", request.sourceLanguageHint());
        }
        String endpoint = config.deeplUseFreeApi ? DEEPL_FREE_ENDPOINT : DEEPL_PRO_ENDPOINT;

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Authorization", AUTH_HEADER_PREFIX + config.deeplApiKey.trim())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "DeepL request failed (" + response.statusCode() + "): " + abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray translations = root.getAsJsonArray("translations");
            if (translations == null || translations.isEmpty()) {
                throw new TranslationException("DeepL returned no translations.");
            }

            JsonObject first = translations.get(0).getAsJsonObject();
            String translated = first.get("text").getAsString();
            String detected = first.has("detected_source_language")
                ? first.get("detected_source_language").getAsString()
                : request.sourceLanguageHint();

            return new TranslationResult(translated, detected, id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("DeepL request failed", e);
        }
    }

    private static void appendForm(StringBuilder builder, String key, String value) {
        if (!builder.isEmpty()) {
            builder.append('&');
        }
        builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
        builder.append('=');
        builder.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
