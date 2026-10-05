package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

public final class AzureTranslatorTranslationProvider implements TranslationProvider {
    private static final String AZURE_TRANSLATE_PATH = "/translate?api-version=3.0";

    @Override
    public String id() {
        return "azure";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        if (config.azureTranslatorApiKey == null || config.azureTranslatorApiKey.isBlank()) {
            throw ProviderHttp.configMissing(id(), "Azure Translator API key is empty. Set it in Chatglot config.");
        }

        String endpoint = buildEndpoint(config.azureTranslatorEndpoint, request);

        JsonArray payload = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("Text", request.text());
        payload.add(item);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Ocp-Apim-Subscription-Key", config.azureTranslatorApiKey.trim())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));

        if (config.azureTranslatorRegion != null && !config.azureTranslatorRegion.isBlank()) {
            requestBuilder.header("Ocp-Apim-Subscription-Region", config.azureTranslatorRegion.trim());
        }

        try {
            HttpResponse<String> response = ProviderHttp.send(requestBuilder.build(), id(), "Azure Translator");
            ProviderHttp.requireSuccess(response, id(), "Azure Translator");

            JsonArray root = ProviderHttp.parseJson(response.body(), id(), "Azure Translator").getAsJsonArray();
            if (root.isEmpty()) {
                throw ProviderHttp.badResponse(id(), "Azure Translator returned no translations.", response.body());
            }

            JsonObject firstItem = root.get(0).getAsJsonObject();
            JsonArray translations = firstItem.getAsJsonArray("translations");
            if (translations == null || translations.isEmpty()) {
                throw ProviderHttp.badResponse(id(), "Azure Translator response has no translation entries.", response.body());
            }

            JsonObject firstTranslation = translations.get(0).getAsJsonObject();
            String translated = firstTranslation.has("text") ? firstTranslation.get("text").getAsString() : "";
            if (translated.isBlank()) {
                throw ProviderHttp.emptyOutput(id(), "Azure Translator returned empty output.");
            }

            String detected = request.sourceLanguageHint();
            if (firstItem.has("detectedLanguage") && firstItem.get("detectedLanguage").isJsonObject()) {
                JsonObject detectedObject = firstItem.getAsJsonObject("detectedLanguage");
                if (detectedObject.has("language")) {
                    detected = detectedObject.get("language").getAsString();
                }
            }

            return new TranslationResult(translated, detected, id());
        } catch (TranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ProviderHttp.badResponse(id(), "Azure Translator returned an invalid response.", e.getMessage());
        }
    }

    private static String buildEndpoint(String configuredEndpoint, TranslationRequest request) {
        String base = configuredEndpoint;
        if (base == null || base.isBlank()) {
            base = ChatglotConfig.AZURE_TRANSLATOR_DEFAULT_ENDPOINT;
        }

        String normalizedBase = base.trim();
        if (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }

        StringBuilder builder = new StringBuilder();
        builder.append(normalizedBase);
        builder.append(AZURE_TRANSLATE_PATH);
        builder.append("&to=");
        builder.append(normalizeLanguageCode(request.targetLanguage()));

        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            builder.append("&from=");
            builder.append(normalizeLanguageCode(request.sourceLanguageHint()));
        }

        return builder.toString();
    }

    private static String normalizeLanguageCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
