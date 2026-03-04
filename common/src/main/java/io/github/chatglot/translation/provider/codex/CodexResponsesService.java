package io.github.chatglot.translation.provider.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.translation.TranslationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class CodexResponsesService {
    private static final String ENDPOINT = "https://chatgpt.com/backend-api/codex/responses";
    private static final String INSTRUCTIONS = "You are OpenCode, a coding assistant.";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public String translate(
        CodexAuthTokens tokens,
        String model,
        String prompt,
        String effort,
        String summary,
        int timeoutSeconds
    ) throws TranslationException {
        if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank()) {
            throw new TranslationException("Codex access token is empty.");
        }

        List<PayloadCandidate> payloads = List.of(
            new PayloadCandidate("responses-rich", createPayload(model, prompt, effort, summary, true)),
            new PayloadCandidate("responses-minimal", createPayload(model, prompt, effort, summary, false))
        );
        List<Boolean> accountHeaderModes = tokens.accountId() == null || tokens.accountId().isBlank()
            ? List.of(false)
            : List.of(true, false);
        List<String> errors = new ArrayList<>();

        for (boolean withAccountHeader : accountHeaderModes) {
            for (PayloadCandidate payload : payloads) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("originator", "opencode")
                    .header("User-Agent", "chatglot-java-codex/1.0")
                    .header("session_id", "session-" + Instant.now().getEpochSecond())
                    .POST(HttpRequest.BodyPublishers.ofString(payload.body().toString(), StandardCharsets.UTF_8));
                if (withAccountHeader) {
                    builder.header("ChatGPT-Account-Id", tokens.accountId());
                }

                try {
                    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() >= 400) {
                        errors.add(
                            "status=" + response.statusCode()
                                + " header.account_id=" + withAccountHeader
                                + " payload=" + payload.label()
                                + " body=" + abbreviate(response.body(), 800)
                        );
                        if (!isPayloadRetryableStatus(response.statusCode())) {
                            break;
                        }
                        continue;
                    }

                    String parsed = parseResponse(
                        response.body(),
                        response.headers().firstValue("Content-Type").orElse("")
                    );
                    if (!parsed.isBlank()) {
                        return parsed;
                    }
                    errors.add(
                        "empty_output status=" + response.statusCode()
                            + " header.account_id=" + withAccountHeader
                            + " payload=" + payload.label()
                    );
                } catch (TranslationException e) {
                    errors.add(
                        "parse_error header.account_id=" + withAccountHeader
                            + " payload=" + payload.label()
                            + " reason=" + e.getMessage()
                    );
                } catch (Exception e) {
                    errors.add(
                        "network_error header.account_id=" + withAccountHeader
                            + " payload=" + payload.label()
                            + " reason=" + e.getMessage()
                    );
                    break;
                }
            }
        }

        String detail = errors.isEmpty() ? "no details" : String.join("\n\n", errors);
        throw new TranslationException("Codex request failed after fallback attempts.\n\n" + detail);
    }

    private static JsonObject createPayload(String model, String prompt, String effort, String summary, boolean includeTruncation) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("input", createInput(prompt));
        payload.addProperty("instructions", INSTRUCTIONS);
        payload.addProperty("store", false);
        payload.addProperty("stream", true);
        if (includeTruncation) {
            payload.addProperty("truncation", "auto");
        }

        JsonObject reasoning = new JsonObject();
        if (effort != null && !effort.isBlank()) {
            reasoning.addProperty("effort", effort);
        }
        if (summary != null && !summary.isBlank()) {
            reasoning.addProperty("summary", summary);
        }
        if (!reasoning.entrySet().isEmpty()) {
            payload.add("reasoning", reasoning);
        }
        return payload;
    }

    private static JsonArray createInput(String prompt) {
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "input_text");
        contentItem.addProperty("text", prompt);

        JsonArray content = new JsonArray();
        content.add(contentItem);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", content);

        JsonArray input = new JsonArray();
        input.add(user);
        return input;
    }

    private static String parseResponse(String body, String contentType) throws TranslationException {
        if (body == null) {
            return "";
        }

        JsonElement asJson = tryParse(body.trim());
        if (asJson != null) {
            String extracted = extractFromJson(asJson);
            if (!extracted.isBlank()) {
                return extracted.trim();
            }
        }

        List<JsonObject> events = parseSseEvents(body);
        if (events.isEmpty()) {
            events = parseNdjsonEvents(body);
        }
        if (!events.isEmpty()) {
            String extracted = extractFromEvents(events);
            if (!extracted.isBlank()) {
                return extracted.trim();
            }
        }

        throw new TranslationException(
            "Unexpected response format from Codex endpoint. content_type="
                + contentType
                + " body_preview="
                + abbreviate(body, 500)
        );
    }

    private static String extractFromJson(JsonElement root) {
        if (root == null || root.isJsonNull()) {
            return "";
        }
        if (root.isJsonPrimitive() && root.getAsJsonPrimitive().isString()) {
            return root.getAsString();
        }
        if (!root.isJsonObject()) {
            return "";
        }

        JsonObject object = root.getAsJsonObject();
        String outputText = readString(object, "output_text");
        if (!outputText.isBlank()) {
            return outputText;
        }

        String fromOutput = extractFromOutputArray(getArray(object, "output"));
        if (!fromOutput.isBlank()) {
            return fromOutput;
        }

        JsonArray events = getArray(object, "events");
        if (events == null || events.isEmpty()) {
            return "";
        }
        List<JsonObject> eventObjects = new ArrayList<>();
        for (JsonElement event : events) {
            if (event != null && event.isJsonObject()) {
                eventObjects.add(event.getAsJsonObject());
            }
        }
        return extractFromEvents(eventObjects);
    }

    private static String extractFromOutputArray(JsonArray output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement outputItem : output) {
            if (outputItem == null || !outputItem.isJsonObject()) {
                continue;
            }
            appendContentTexts(builder, getArray(outputItem.getAsJsonObject(), "content"));
        }
        return builder.toString();
    }

    private static List<JsonObject> parseSseEvents(String body) {
        List<JsonObject> events = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring(5).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonElement parsed = tryParse(data);
            if (parsed != null && parsed.isJsonObject()) {
                events.add(parsed.getAsJsonObject());
            }
        }
        return events;
    }

    private static List<JsonObject> parseNdjsonEvents(String body) {
        List<JsonObject> events = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            JsonElement parsed = tryParse(trimmed);
            if (parsed != null && parsed.isJsonObject()) {
                events.add(parsed.getAsJsonObject());
            }
        }
        return events;
    }

    private static String extractFromEvents(List<JsonObject> events) {
        StringBuilder builder = new StringBuilder();
        boolean sawDelta = false;
        for (JsonObject event : events) {
            String type = readString(event, "type");
            if ("response.output_text.delta".equals(type)) {
                String delta = readString(event, "delta");
                if (!delta.isBlank()) {
                    sawDelta = true;
                    builder.append(delta);
                }
                continue;
            }

            if ("response.output_item.done".equals(type) && !sawDelta) {
                JsonObject item = getObject(event, "item");
                appendContentTexts(builder, getArray(item, "content"));
            }
        }
        return builder.toString();
    }

    private static void appendContentTexts(StringBuilder builder, JsonArray content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        for (JsonElement contentElement : content) {
            if (contentElement == null || !contentElement.isJsonObject()) {
                continue;
            }
            JsonObject part = contentElement.getAsJsonObject();
            String type = readString(part, "type");
            if (!"output_text".equals(type) && !"text".equals(type)) {
                continue;
            }
            String text = readString(part, "text");
            if (!text.isBlank()) {
                builder.append(text);
            }
        }
    }

    private static JsonElement tryParse(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isPayloadRetryableStatus(int statusCode) {
        return statusCode == 400 || statusCode == 404 || statusCode == 422;
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static JsonArray getArray(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }

    private static String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private record PayloadCandidate(String label, JsonObject body) {
    }
}
