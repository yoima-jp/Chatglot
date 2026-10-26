package io.github.chatglot.translation.provider;

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

public final class GasTranslationProvider implements TranslationProvider {
    private static final int MAX_REDIRECTS = 5;

    @Override
    public String id() {
        return "gas";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        String endpoint = resolveEndpoint(config);
        if (endpoint.isBlank()) {
            throw ProviderHttp.configMissing(id(), "GAS Web App URL is empty. Set it in Chatglot config.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("text", request.text());
        payload.addProperty("target", normalizeLanguageCode(request.targetLanguage()));
        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            payload.addProperty("source", normalizeLanguageCode(request.sourceLanguageHint()));
        }

        try {
            HttpResponse<String> response = sendWithRedirects(
                endpoint,
                payload.toString(),
                config.requestTimeoutSeconds
            );
            ProviderHttp.requireSuccess(response, id(), "GAS");

            String responseBody = response.body() == null ? "" : response.body();
            String normalizedBody = normalizeJsonBody(responseBody);

            JsonObject root;
            try {
                root = ProviderHttp.parseJson(normalizedBody, id(), "GAS").getAsJsonObject();
            } catch (TranslationException parseError) {
                if (parseError.kind() == TranslationException.Kind.BAD_RESPONSE) {
                    throw ProviderHttp.badResponse(id(), "GAS returned a non-JSON response.", buildNonJsonResponseMessage(response, responseBody));
                }
                throw parseError;
            }

            if (root.has("ok") && !root.get("ok").getAsBoolean()) {
                String detail = extractErrorMessage(root);
                String normalizedError = (detail + " " + root).toLowerCase(Locale.ROOT);
                boolean rateLimited = normalizedError.contains("rate limit")
                    || normalizedError.contains("rate_limit")
                    || normalizedError.contains("too many requests")
                    || normalizedError.contains("429")
                    || normalizedError.contains("resource_exhausted");
                throw TranslationException.classified(
                    rateLimited ? TranslationException.Kind.RATE_LIMITED : TranslationException.Kind.SERVER,
                    "GAS translation failed.",
                    id(),
                    null,
                    null,
                    ProviderHttp.maskAndLimit(detail),
                    null
                );
            }

            String translated = getString(root, "translatedText");
            if (translated.isBlank()) {
                throw ProviderHttp.emptyOutput(id(), "GAS translation returned empty output.");
            }

            String detected = getString(root, "source");
            if (detected.isBlank() || detected.equalsIgnoreCase("auto")) {
                detected = request.sourceLanguageHint();
            }

            return new TranslationResult(translated, detected, id());
        } catch (TranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ProviderHttp.badResponse(id(), "GAS returned an invalid response.", e.getMessage());
        }
    }

    private static String resolveEndpoint(ChatglotConfig config) {
        if (config == null) {
            return "";
        }
        if (config.gasWebAppUrl == null) {
            return "";
        }
        return config.gasWebAppUrl.trim();
    }

    private HttpResponse<String> sendWithRedirects(String url, String requestBody, int timeoutSeconds)
        throws TranslationException {
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

            HttpResponse<String> response = ProviderHttp.send(request, id(), "GAS");
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

        throw ProviderHttp.badResponse(id(), "GAS exceeded the redirect limit.", null);
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

    private static String buildNonJsonResponseMessage(HttpResponse<String> response, String responseBody) {
        String contentType = readContentType(response);
        String details = TranslationPromptBuilder.abbreviate(responseBody, 500);
        if (isHtmlResponse(contentType, responseBody)) {
            String htmlText = extractHtmlText(responseBody);
            if (!htmlText.isBlank()) {
                details = TranslationPromptBuilder.abbreviate(htmlText, 500);
            }
            details = details
                + " Check that the GAS Web App URL ends with /exec, the deployment is a Web app, "
                + "Execute as is set to Me, Who has access is set to Anyone, and the script has been authorized.";
        }

        return "GAS returned non-JSON response ("
            + response.statusCode()
            + ", "
            + contentType
            + "): "
            + details;
    }

    private static boolean isHtmlResponse(String contentType, String responseBody) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return true;
        }
        if (responseBody == null) {
            return false;
        }
        return responseBody.stripLeading().toLowerCase(Locale.ROOT).startsWith("<!doctype html")
            || responseBody.stripLeading().toLowerCase(Locale.ROOT).startsWith("<html");
    }

    private static String extractHtmlText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        String text = html
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String extractErrorMessage(JsonObject root) {
        String message = getString(root, "message");
        String details = getString(root, "details");
        String error = errorDetail(root.get("error"));

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

    private static String errorDetail(com.google.gson.JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString().trim();
        }
        if (value.isJsonObject()) {
            JsonObject error = value.getAsJsonObject();
            String message = getString(error, "message");
            String code = getString(error, "code");
            if (message.isBlank()) {
                return code;
            }
            return code.isBlank() ? message : code + " - " + message;
        }
        return value.toString();
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
