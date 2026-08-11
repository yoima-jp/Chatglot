package io.github.chatglot.translation.provider.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.translation.TranslationException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodexResponsesService} SSE / JSON response parsing.
 *
 * <p>The production class parses the upstream Codex endpoint response through a private
 * {@code parseResponse(String body, String contentType)} method. That method is the single
 * seam turning a raw HTTP body into the translated text. It first classifies the body as
 * SSE or non-SSE via {@code isSseResponse} (Content-Type containing text/event-stream, or
 * a body whose first non-blank line starts with {@code event:}/{@code data:}), then either
 * parses concatenated {@code data:} lines per SSE event or NDJSON lines. Delta events are
 * concatenated, error events surface as {@link TranslationException}, and a body whose
 * Content-Type is empty is still classified from its shape. Exercising this method directly
 * via reflection keeps the public {@code translate(...)} API (which performs real HTTP
 * calls) out of the unit tests while still covering the parsing contract.</p>
 *
 * <p>Reflection is used intentionally rather than widening visibility: the parser is an
 * implementation detail and the public API should stay minimal. The existing ModDeck test
 * in this module already follows the same reflection pattern for the same reason.</p>
 */
class CodexResponsesServiceTest {

    /**
     * Invokes the private {@code parseResponse(String, String)} method.
     *
     * <p>{@code parseResponse} is the only entry point that materializes the SSE/JSON
     * parsing contract. Calling it directly avoids going through {@code translate(...)},
     * which would require mocking the Java {@link java.net.http.HttpClient}. The method
     * is static so no instance is needed.</p>
     */
    private static String parseResponse(String body, String contentType) throws Throwable {
        Method method = CodexResponsesService.class.getDeclaredMethod("parseResponse", String.class, String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, body, contentType);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Re-throw the underlying cause so tests can assert on the real exception type.
            throw e.getCause();
        }
    }

    @Test
    void parsesSseDeltaEventsAndConcatenates() throws Throwable {
        // Multiple response.output_text.delta events must be concatenated in order to form
        // the final translated text. Each event is delimited by a blank line; parseSseEvents
        // accumulates consecutive data: lines within one event, so events must be separated
        // by blank lines. The [DONE] sentinel and non-data lines (event: label) are ignored.
        String sse = String.join("\n",
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}",
            "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\", \"}",
            "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"world!\"}",
            "",
            "data: [DONE]",
            ""
        );

        assertEquals("Hello, world!", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void parsesSingleSseDeltaEvent() throws Throwable {
        // A single delta event should still produce its delta content without trailing
        // whitespace from the SSE framing.
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"translated text\"}\n\n";

        assertEquals("translated text", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void fallsBackToOutputItemDoneWhenNoDeltas() throws Throwable {
        // When the stream contains no delta events (e.g. a final-only response), the
        // parser falls back to response.output_item.done and reads the text from
        // item.content, where parts may use either "output_text" or "text". The JSON
        // payload is kept on a single line because parseSseEvents parses each event's
        // joined data: lines as one JSON value and does not reassemble pretty-printed
        // objects across blank-line-delimited events.
        String sse = String.join("\n",
            "event: response.output_item.done",
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"fallback output\"}]}}",
            ""
        );

        assertEquals("fallback output", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void outputItemDoneIsIgnoredWhenDeltasWereSeen() throws Throwable {
        // Once at least one delta event has been observed (sawDelta flag set by the
        // presence of a delta field), response.output_item.done must not append its
        // content again. This prevents duplicated text when both delta and done events are
        // present in the same stream (the common case).
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"only deltas\"}",
            "",
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"content\":[{\"type\":\"output_text\",\"text\":\"should be ignored\"}]}}",
            ""
        );

        assertEquals("only deltas", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void outputItemDoneAcceptsTextTypeParts() throws Throwable {
        // Some Codex payloads tag content parts with "text" instead of "output_text".
        // Both shapes are accepted by appendContentTexts; this verifies the "text" branch
        // via the fallback path. Single-line JSON, same reason as above.
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"content\":[{\"type\":\"text\",\"text\":\"text-typed part\"}]}}",
            ""
        );

        assertEquals("text-typed part", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void skipsBlankAndNonJsonDataLines() throws Throwable {
        // Whitespace-only or malformed data: payloads form their own (unparseable) events
        // that are skipped, and a subsequent valid event still parses. Each event is on
        // its own line separated by a blank line; the two valid deltas must concatenate.
        String sse = String.join("\n",
            "data:    ",
            "",
            "data: not-json",
            "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"keep\"}",
            "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"going\"}",
            ""
        );

        assertEquals("keepgoing", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void doneSentinelDoesNotCorruptOutput() throws Throwable {
        // [DONE] is the SSE terminator and must not be treated as a JSON event or appended.
        // It must be its own event (blank line separated) so it is recognized as the
        // sentinel rather than being concatenated into a neighboring data: payload. The
        // deltas around it then concatenate; [DONE] contributes nothing.
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"before-done\"}",
            "",
            "data: [DONE]",
            "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"after-done\"}",
            ""
        );

        assertEquals("before-doneafter-done", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void fallsBackToNdjsonWhenNoSseDataLines() throws Throwable {
        // When the body is neither SSE-shaped (no leading event:/data:) nor a single JSON
        // value, but is line-delimited JSON objects, parseNdjsonEvents handles it. This
        // covers endpoints/proxies that strip the SSE framing and send raw NDJSON.
        String ndjson = String.join("\n",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"ndjson-1\"}",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"ndjson-2\"}"
        );

        assertEquals("ndjson-1ndjson-2", parseResponse(ndjson, "application/x-ndjson"));
    }

    @Test
    void parsesJsonObjectResponseWithOutputText() throws Throwable {
        // A plain JSON object (non-SSE) response carries the final text in output_text.
        // This is the non-streaming Codex shape; parseResponse tries JSON first.
        assertEquals("plain json output",
            parseResponse("{\"output_text\":\"plain json output\"}", "application/json"));
    }

    @Test
    void parsesJsonObjectResponseWithOutputArray() throws Throwable {
        // When output_text is absent, text is reconstructed from the output array's
        // content parts. This mirrors the Responses API message-item shape.
        String json = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"array output\"}]}]}";

        assertEquals("array output", parseResponse(json, "application/json"));
    }

    @Test
    void parsesJsonObjectResponseWithEventsArray() throws Throwable {
        // Some Codex responses embed an events array inside a JSON object. The parser
        // routes that array through the same extractFromEvents logic as SSE.
        String json = "{\"events\":["
            + "{\"type\":\"response.output_text.delta\",\"delta\":\"event-1\"},"
            + "{\"type\":\"response.output_text.delta\",\"delta\":\"event-2\"}"
            + "]}";

        assertEquals("event-1event-2", parseResponse(json, "application/json"));
    }

    @Test
    void parsesJsonPrimitiveStringResponse() throws Throwable {
        // A bare JSON string primitive is accepted as the entire translated output.
        assertEquals("primitive string",
            parseResponse("\"primitive string\"", "application/json"));
    }

    @Test
    void parsesJsonEvenWithEmptyContentType() throws Throwable {
        // Some proxies/gateways strip Content-Type or send it empty. Parsing must succeed
        // purely from the body shape. Here a JSON object with output_text is detected even
        // though the content type argument is empty.
        assertEquals("no content type",
            parseResponse("{\"output_text\":\"no content type\"}", ""));
    }

    @Test
    void parsesSseEvenWithEmptyContentType() throws Throwable {
        // Same body-shape-driven behavior for SSE streams when Content-Type is empty but
        // the body starts with data:, so isSseResponse classifies it as SSE.
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"sse no ct\"}\n\n";

        assertEquals("sse no ct", parseResponse(sse, ""));
    }

    @Test
    void parsesSseWithNullContentType() throws Throwable {
        // translate(...) passes the header via orElse(""), but parseResponse also receives
        // the raw value in other call paths. A null content type must not cause an NPE;
        // isSseResponse handles null contentType and the body is still parsed.
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"null ct\"}\n\n";

        assertEquals("null ct", parseResponse(sse, null));
    }

    @Test
    void classifiesAsSseFromLeadingEventLineEvenWithEmptyContentType() throws Throwable {
        // isSseResponse falls back to inspecting the body when Content-Type is absent/empty:
        // a body whose first non-blank line starts with "event:" is treated as SSE. This
        // covers proxies that drop the Content-Type header but forward the SSE body. The
        // event label line itself is ignored by parseSseEvents; only the following data:
        // line carries the payload.
        String sse = String.join("\n",
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"event classified\"}",
            ""
        );

        assertEquals("event classified", parseResponse(sse, ""));
    }

    @Test
    void throwsOnUnrecognizedErrorEventBody() throws Throwable {
        // An error event in the stream carries no translatable text. extractFromEvents
        // detects error/response.error/response.failed events and raises a
        // TranslationException whose message is formatted by formatCodexError, carrying the
        // upstream message rather than the generic "Unexpected response format".
        String sse = "data: {\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}\n\n";

        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse(sse, "text/event-stream"));

        // The message is the structured Codex error form so callers can surface upstream
        // failures verbatim.
        assertTrue(ex.getMessage().contains("Codex streaming error"), ex.getMessage());
        assertTrue(ex.getMessage().contains("rate limited"), ex.getMessage());
    }

    @Test
    void throwsOnResponseErrorEventType() throws Throwable {
        // The error detector also matches the "response.error" type, used by some Codex
        // streams. The message is read from the nested error object's message field.
        String sse = "data: {\"type\":\"response.error\",\"error\":{\"message\":\"bad token\"}}\n\n";

        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse(sse, "text/event-stream"));

        assertTrue(ex.getMessage().contains("Codex streaming error"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bad token"), ex.getMessage());
    }

    @Test
    void throwsOnErrorResponseFailedType() throws Throwable {
        // "response.failed" is the third error variant the detector matches. When no
        // message is present the formatter falls back to the serialized event so callers
        // still get a non-empty diagnostic.
        String sse = "data: {\"type\":\"response.failed\"}\n\n";

        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse(sse, "text/event-stream"));

        assertTrue(ex.getMessage().contains("Codex streaming error"), ex.getMessage());
        assertTrue(ex.getMessage().contains("response.failed"), ex.getMessage());
    }

    @Test
    void errorEventAlongsideDeltasThrows() throws Throwable {
        // An error event is surfaced immediately even when deltas were already seen:
        // extractFromEvents checks isErrorEvent before processing delta content, so the
        // first error event aborts the stream. This documents the current contract: any
        // error event in the stream is fatal. (If this becomes non-fatal, update this
        // alongside the parser.)
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
            "",
            "data: {\"type\":\"error\",\"error\":{\"message\":\"too many requests\"}}",
            ""
        );

        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse(sse, "text/event-stream"));

        assertTrue(ex.getMessage().contains("too many requests"), ex.getMessage());
    }

    @Test
    void parsesResponseOutputTextDoneEvent() throws Throwable {
        // response.output_text.done carries the final text in its top-level "text" field
        // and is used when no delta events are emitted. This verifies the done-text branch.
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_text.done\",\"text\":\"done text\"}",
            ""
        );

        assertEquals("done text", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void parsesResponseCompletedWithResponseObject() throws Throwable {
        // response.completed/response.done events may carry the full response object; the
        // parser routes its "response" field through extractFromJson. Here the embedded
        // response exposes output_text, which is returned when no deltas were seen.
        String sse = String.join("\n",
            "data: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"completed output\"}}",
            ""
        );

        assertEquals("completed output", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void concatenatesMultiLineDataPayloads() throws Throwable {
        // parseSseEvents joins consecutive data: lines of a single event with newlines
        // before parsing, allowing a JSON object split across multiple data: lines. This
        // matches the SSE spec where a multi-line data payload is reassembled by the
        // client. Here a pretty-printed delta object is split across two data: lines.
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_text.delta\",",
            "data:  \"delta\":\"joined lines\"}",
            ""
        );

        assertEquals("joined lines", parseResponse(sse, "text/event-stream"));
    }

    @Test
    void ndjsonErrorEventThrows() throws Throwable {
        // The error detector applies to NDJSON-parsed events too, so a non-SSE error line
        // surfaces the same Codex streaming error. The body starts with "{" so it is not
        // classified as SSE; parseNdjsonEvents processes it and extractFromEvents throws.
        String ndjson = "{\"type\":\"error\",\"error\":{\"message\":\"ndjson failure\"}}";

        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse(ndjson, "application/x-ndjson"));

        assertTrue(ex.getMessage().contains("ndjson failure"), ex.getMessage());
    }

    @Test
    void parsesJsonObjectErrorThrowsStructuredMessage() throws Throwable {
        // A top-level JSON object with an error type is detected by extractFromJson before
        // any output_text/output inspection, so a plain JSON error response surfaces the
        // structured Codex error message rather than the generic format exception.
        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse("{\"type\":\"error\",\"message\":\"json error\"}", "application/json"));

        assertTrue(ex.getMessage().contains("Codex streaming error"), ex.getMessage());
        assertTrue(ex.getMessage().contains("json error"), ex.getMessage());
    }

    @Test
    void throwsOnEmptyBody() throws Throwable {
        // An empty body yields no JSON and no events. This must raise rather than silently
        // return an empty string, because an empty translation is indistinguishable from a
        // parsing bug. An empty body is not classified as SSE (no leading event:/data:), so
        // it goes through the JSON then NDJSON paths, both empty, and raises the generic
        // unexpected-format exception.
        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse("", "text/event-stream"));

        assertTrue(ex.getMessage().contains("Unexpected response format"), ex.getMessage());
    }

    @Test
    void nullBodyReturnsEmptyString() throws Throwable {
        // parseResponse guards null body explicitly and returns "" immediately. This
        // documents the current contract: a null body is treated as "nothing to parse"
        // rather than an error. (The translate(...) caller handles an empty result by
        // recording an empty_output error and retrying.)
        //
        // If this guard is ever removed, replace this test with one asserting that null
        // raises TranslationException.
        assertEquals("", parseResponse(null, "text/event-stream"));
    }

    @Test
    void throwsOnGarbageBodyWithEmptyContentType() throws Throwable {
        // A body that is neither valid JSON nor SSE/NDJSON must raise, and the message
        // must carry the (possibly empty) content type and a preview. The body does not
        // start with event:/data: so it is treated as non-SSE and fails both JSON and
        // NDJSON parsing.
        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse("not json and not sse", ""));

        assertTrue(ex.getMessage().contains("content_type="), ex.getMessage());
        assertTrue(ex.getMessage().contains("body_preview="), ex.getMessage());
    }

    @Test
    void sseStreamWithOnlyDoneReturnsEmptyAndRaises() throws Throwable {
        // A stream consisting solely of the [DONE] sentinel has no parseable events, so
        // parseResponse falls through to the unexpected-format exception. This documents
        // that [DONE] alone is not a valid translation result.
        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse("data: [DONE]\n\n", "text/event-stream"));

        assertTrue(ex.getMessage().contains("Unexpected response format"), ex.getMessage());
    }

    @Test
    void ignoresEmptyDeltaString() throws Throwable {
        // A delta event with an empty (but present) delta field sets sawDelta but appends
        // nothing, so a following output_item.done is skipped. The result is empty, which
        // surfaces as the unexpected-format exception rather than a partial translation.
        String sse = String.join("\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"\"}",
            "",
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"content\":[{\"type\":\"output_text\",\"text\":\"ignored\"}]}}",
            ""
        );

        TranslationException ex = assertThrows(TranslationException.class,
            () -> parseResponse(sse, "text/event-stream"));

        assertTrue(ex.getMessage().contains("Unexpected response format"), ex.getMessage());
    }

    @Test
    void parseSseEventsHelperJoinsConsecutiveDataLines() throws Throwable {
        // Directly exercises parseSseEvents to pin the multi-line-join behavior: two
        // consecutive data: lines followed by a blank line produce a single event object.
        // This guards the SSE reassembly contract independently of extractFromEvents.
        Method method = CodexResponsesService.class.getDeclaredMethod("parseSseEvents", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<com.google.gson.JsonObject> events = (List<com.google.gson.JsonObject>) method.invoke(null,
            "data: {\"a\":1,\ndata:  \"b\":2}\n\n");

        assertEquals(1, events.size(), "consecutive data lines should form one event");
        assertEquals(2, events.get(0).size(), "joined payload should parse both fields");
    }
}
