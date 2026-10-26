package io.github.chatglot.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationException.Kind;
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

/** Uses Google's unofficial, keyless web translation endpoint. */
public final class GoogleWebTranslationProvider implements TranslationProvider {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/";

    @Override
    public String id() {
        return "google_web";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        String source = toGoogleSource(request.sourceLanguageHint());
        String target = toGoogleTarget(request.targetLanguage());
        HttpResponse<String> primary = ProviderHttp.send(
            HttpRequest.newBuilder(URI.create(ENDPOINT + "t?client=gtx&sl=" + source + "&tl=" + target))
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "q=" + URLEncoder.encode(request.text(), StandardCharsets.UTF_8), StandardCharsets.UTF_8
                ))
                .build(),
            id(),
            "Google Translate"
        );
        requireUsableResponse(primary);

        ParsedTranslation parsed;
        try {
            parsed = parsePrimaryResponse(primary.body(), "auto".equals(source));
        } catch (RuntimeException malformed) {
            // The legacy endpoint is used only for an unusable response shape; transport and HTTP failures
            // must not trigger a second request and amplify throttling.
            return translateWithSingle(request, config, source, target);
        }
        if (parsed.translatedText().isBlank()) {
            return translateWithSingle(request, config, source, target);
        }

        String detected = parsed.detectedSourceLanguage().isBlank()
            ? request.sourceLanguageHint()
            : parsed.detectedSourceLanguage();
        return new TranslationResult(parsed.translatedText(), detected, id());
    }

    private TranslationResult translateWithSingle(
        TranslationRequest request,
        ChatglotConfig config,
        String source,
        String target
    ) throws TranslationException {
        String query = "client=gtx&sl=" + source + "&tl=" + target + "&dt=t&q="
            + URLEncoder.encode(request.text(), StandardCharsets.UTF_8);
        HttpResponse<String> response = ProviderHttp.send(
            HttpRequest.newBuilder(URI.create(ENDPOINT + "single?" + query))
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build(),
            id(),
            "Google Translate"
        );
        requireUsableResponse(response);

        try {
            ParsedTranslation parsed = parseSingleResponse(response.body());
            if (parsed.translatedText().isBlank()) {
                throw ProviderHttp.emptyOutput(id(), "Google Translate returned empty output.");
            }
            String detected = parsed.detectedSourceLanguage().isBlank()
                ? request.sourceLanguageHint()
                : parsed.detectedSourceLanguage();
            return new TranslationResult(parsed.translatedText(), detected, id());
        } catch (TranslationException exception) {
            throw exception;
        } catch (RuntimeException malformed) {
            throw ProviderHttp.badResponse(id(), "Google Translate returned an invalid response.", response.body());
        }
    }

    private void requireUsableResponse(HttpResponse<String> response) throws TranslationException {
        int status = response.statusCode();
        if (status == 429 || isSorryRedirect(response) || isSorryHtml(response)) {
            throw TranslationException.classified(
                Kind.RATE_LIMITED,
                "Google Translate is temporarily rate limited.",
                id(),
                status,
                ProviderHttp.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null)).orElse(null),
                ProviderHttp.maskAndLimit(response.body()),
                null
            );
        }
        ProviderHttp.requireSuccess(response, id(), "Google Translate");
    }

    private static boolean isSorryRedirect(HttpResponse<String> response) {
        return response.headers().firstValue("Location")
            .map(location -> location.toLowerCase(Locale.ROOT).contains("google.com/sorry"))
            .orElse(false);
    }

    private static boolean isSorryHtml(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return false;
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        String normalized = body.stripLeading().toLowerCase(Locale.ROOT);
        boolean html = contentType.contains("text/html") || normalized.startsWith("<!doctype html") || normalized.startsWith("<html");
        return html && normalized.contains("sorry") && (
            normalized.contains("google")
                || normalized.contains("unusual traffic")
                || normalized.contains("not a robot")
                || normalized.contains("automated requests")
        );
    }

    static ParsedTranslation parsePrimaryResponse(String body, boolean detectionExpected) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("Unexpected Google Translate response shape");
        }
        JsonArray array = root.getAsJsonArray();
        JsonElement first = array.get(0);
        if (!detectionExpected && first.isJsonPrimitive() && first.getAsJsonPrimitive().isString()) {
            StringBuilder translated = new StringBuilder();
            for (JsonElement part : array) {
                if (part.isJsonPrimitive() && part.getAsJsonPrimitive().isString()) {
                    translated.append(part.getAsString());
                } else {
                    throw new IllegalArgumentException("Unexpected Google Translate text response shape");
                }
            }
            return new ParsedTranslation(translated.toString(), "");
        }
        if (detectionExpected && first.isJsonArray() && first.getAsJsonArray().size() >= 2
            && isStringAt(first.getAsJsonArray(), 0) && isStringAt(first.getAsJsonArray(), 1)) {
            JsonArray translation = first.getAsJsonArray();
            return new ParsedTranslation(stringAt(translation, 0), stringAt(translation, 1));
        }
        throw new IllegalArgumentException("Unexpected Google Translate response shape");
    }

    static ParsedTranslation parseSingleResponse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty() || !root.getAsJsonArray().get(0).isJsonArray()) {
            throw new IllegalArgumentException("Unexpected Google Translate single response shape");
        }
        StringBuilder translated = new StringBuilder();
        for (JsonElement segment : root.getAsJsonArray().get(0).getAsJsonArray()) {
            if (segment.isJsonArray()) {
                translated.append(stringAt(segment.getAsJsonArray(), 0));
            }
        }
        String detected = root.getAsJsonArray().size() > 2 && root.getAsJsonArray().get(2).isJsonPrimitive()
            && root.getAsJsonArray().get(2).getAsJsonPrimitive().isString()
            ? root.getAsJsonArray().get(2).getAsString()
            : "";
        return new ParsedTranslation(translated.toString(), detected);
    }

    private static String stringAt(JsonArray array, int index) {
        if (!isStringAt(array, index)) {
            return "";
        }
        return array.get(index).getAsString();
    }

    private static boolean isStringAt(JsonArray array, int index) {
        return index < array.size() && array.get(index).isJsonPrimitive()
            && array.get(index).getAsJsonPrimitive().isString();
    }

    private static String toGoogleTarget(String value) {
        String normalized = LanguageUtil.normalizeTargetLanguage(value);
        return switch (normalized) {
            case "ZH-HANS" -> "zh-CN";
            case "ZH-HANT" -> "zh-TW";
            default -> baseLanguage(normalized);
        };
    }

    private static String toGoogleSource(String value) {
        if (value == null || value.isBlank()) {
            return "auto";
        }
        String normalized = LanguageUtil.normalize(value);
        if (normalized.equals("AUTO")) {
            return "auto";
        }
        return switch (normalized) {
            case "ZH-CN", "ZH-SG", "ZH-HANS" -> "zh-CN";
            case "ZH-TW", "ZH-HK", "ZH-MO", "ZH-HANT" -> "zh-TW";
            default -> {
                String base = normalized.split("-", 2)[0];
                yield base.matches("[A-Z]{2,3}") ? base.toLowerCase(Locale.ROOT) : "auto";
            }
        };
    }

    private static String baseLanguage(String normalized) {
        String base = normalized.split("-", 2)[0];
        return base.toLowerCase(Locale.ROOT);
    }

    record ParsedTranslation(String translatedText, String detectedSourceLanguage) { }
}
