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
    private static final int MAX_REDIRECTS = 5;
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

        try {
            HttpResponse<String> response = sendWithRedirects(
                config.gasWebAppUrl.trim(),
                payload.toString(),
                config.requestTimeoutSeconds
            );
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "GAS translation request failed ("
                        + response.statusCode()
                        + "): "
                        + TranslationPromptBuilder.abbreviate(response.body(), 500)
                );
            }

            String responseBody = response.body() == null ? "" : response.body();
            String normalizedBody = normalizeJsonBody(responseBody);

            JsonObject root;
            try {
                root = JsonParser.parseString(normalizedBody).getAsJsonObject();
            } catch (RuntimeException parseError) {
                throw new TranslationException(
                    "GAS returned non-JSON response ("
                        + response.statusCode()
                        + ", "
                        + readContentType(response)
                        + "): "
                        + TranslationPromptBuilder.abbreviate(responseBody, 500),
                    parseError
                );
            }

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

    private HttpResponse<String> sendWithRedirects(String url, String requestBody, int timeoutSeconds) throws Exception {
        URI currentUri = URI.create(url);
        boolean usePost = true;

        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(currentUri)
                .timeout(Duration.ofSeconds(timeoutSeconds));
            HttpRequest request;
            if (usePost) {
                request = builder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            } else {
                request = builder.GET().build();
            }

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response;
            }

            String location = response.headers().firstValue("Location").orElse("");
            if (location.isBlank()) {
                return response;
            }

            // GAS /exec commonly redirects POST (302) to a googleusercontent URL that should be fetched with GET.
            if (status == 301 || status == 302 || status == 303) {
                usePost = false;
            }
            currentUri = currentUri.resolve(location.trim());
        }

        throw new TranslationException("GAS translation request failed: too many redirects.");
    }

    private static String normalizeJsonBody(String body) {
        if (body == null) {
            return "";
        }

        String normalized = body.stripLeading();
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1).stripLeading();
        }

        if (normalized.startsWith(")]}'")) {
            int lineBreak = normalized.indexOf('\n');
            if (lineBreak >= 0) {
                normalized = normalized.substring(lineBreak + 1).stripLeading();
            } else {
                normalized = normalized.substring(4).stripLeading();
            }
        }

        return normalized;
    }

    private static String readContentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("unknown");
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
