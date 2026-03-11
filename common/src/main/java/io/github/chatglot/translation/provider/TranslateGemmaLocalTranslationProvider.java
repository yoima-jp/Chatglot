package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.localbackend.LocalBackendManager;
import io.github.chatglot.localbackend.LocalBackendStatus;
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
import java.util.List;
import java.util.Locale;

public final class TranslateGemmaLocalTranslationProvider implements TranslationProvider {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final LocalBackendManager backendManager;

    public TranslateGemmaLocalTranslationProvider(LocalBackendManager backendManager) {
        this.backendManager = backendManager;
    }

    @Override
    public String id() {
        return "translategemma_local";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        LocalBackendStatus status = backendManager.ensureBackendAvailable(config);
        if (!status.healthy()) {
            throw new TranslationException("Local backend unavailable: " + status.message());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("prompt", buildTranslateGemmaPrompt(request));
        payload.addProperty("n_predict", 256);
        payload.addProperty("temperature", 0.1);
        payload.addProperty("cache_prompt", true);
        JsonArray stop = new JsonArray();
        for (String stopToken : List.of("<end_of_turn>", "<eos>", "</s>")) {
            stop.add(stopToken);
        }
        payload.add("stop", stop);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(status.backendUrl() + "/completion"))
            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "Local backend request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String translated = extractTranslatedText(root);
            if (translated.isBlank()) {
                throw new TranslationException("Local backend returned empty translation.");
            }
            return new TranslationResult(translated, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Local backend request failed", e);
        }
    }

    private static String resolveModel(ChatglotConfig config) {
        if (config.localModelAlias != null && !config.localModelAlias.isBlank()) {
            return config.localModelAlias.trim();
        }
        return ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_ALIAS;
    }

    private static String buildTranslateGemmaPrompt(TranslationRequest request) {
        String sourceCode = toTranslateGemmaLanguageCode(request.sourceLanguageHint(), "en");
        String targetCode = toTranslateGemmaLanguageCode(request.targetLanguage(), "ja");
        String sourceLanguage = toLanguageDisplayName(sourceCode);
        String targetLanguage = toLanguageDisplayName(targetCode);
        return "<bos><start_of_turn>user\n"
            + "You are a professional "
            + sourceLanguage
            + " ("
            + sourceCode
            + ") to "
            + targetLanguage
            + " ("
            + targetCode
            + ") translator. "
            + "Produce only the "
            + targetLanguage
            + " translation, without any additional explanations or commentary. "
            + "Preserve player names, commands, URLs, placeholders, and formatting markers. "
            + "If markers like [[CGT_0]]...[[/CGT_0]] appear, keep the markers exactly as-is and place translated text only between matching markers.\n\n"
            + request.text().trim()
            + "\n<end_of_turn>\n<start_of_turn>model\n";
    }

    private static String toTranslateGemmaLanguageCode(String language, String fallback) {
        if (language == null || language.isBlank()) {
            return fallback;
        }

        String normalized = language.trim().replace('_', '-');
        normalized = switch (normalized.toUpperCase(Locale.ROOT)) {
            case "ZH-HANS", "ZH-CN", "ZH-SG" -> "zh-Hans";
            case "ZH-HANT", "ZH-TW", "ZH-HK", "ZH-MO" -> "zh-Hant";
            case "EN-US" -> "en-US";
            case "EN-GB" -> "en-GB";
            case "PT-BR" -> "pt-BR";
            case "PT-PT" -> "pt-PT";
            default -> normalized.toLowerCase(Locale.ROOT);
        };
        return normalized;
    }

    private static String toLanguageDisplayName(String languageCode) {
        return switch (languageCode) {
            case "zh-Hans" -> "Simplified Chinese";
            case "zh-Hant" -> "Traditional Chinese";
            default -> {
                Locale locale = Locale.forLanguageTag(languageCode);
                String displayName = locale.getDisplayLanguage(Locale.ENGLISH);
                yield displayName == null || displayName.isBlank() ? languageCode : displayName;
            }
        };
    }

    private static String extractTranslatedText(JsonObject root) throws TranslationException {
        if (root.has("content") && root.get("content").isJsonPrimitive()) {
            return root.get("content").getAsString().trim();
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new TranslationException("Local backend returned no choices.");
        }

        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) {
            throw new TranslationException("Local backend returned an invalid response.");
        }
        JsonObject firstObject = first.getAsJsonObject();
        JsonElement content = null;
        JsonObject message = firstObject.getAsJsonObject("message");
        if (message != null && message.has("content")) {
            content = message.get("content");
        } else if (firstObject.has("text")) {
            content = firstObject.get("text");
        }
        if (content == null) {
            throw new TranslationException("Local backend response missing content.");
        }

        if (content.isJsonPrimitive()) {
            return content.getAsString().trim();
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement element : content.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject part = element.getAsJsonObject();
                if (!part.has("text") || !part.get("text").isJsonPrimitive()) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(part.get("text").getAsString());
            }
            return builder.toString().trim();
        }

        return "";
    }
}
