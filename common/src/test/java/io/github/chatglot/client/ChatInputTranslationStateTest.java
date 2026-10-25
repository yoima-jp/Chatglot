package io.github.chatglot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatInputTranslationStateTest {
    private final ChatInputTranslationState state = new ChatInputTranslationState();

    @Test
    void dropsResultsFromOlderRequests() {
        var first = state.start("hello", "en", "gas");
        var latest = state.start("hello", "en", "gas");

        assertFalse(state.complete(first, "stale", null));
        assertTrue(state.complete(latest, "current", null));
        assertEquals("current", state.translation());
    }

    @Test
    void inputEditsMakeThePreviewStale() {
        var request = state.start("hello", "en", "gas");
        state.complete(request, "こんにちは", null);

        state.observe("hello!", "en");

        assertEquals(ChatInputTranslationState.Status.HIDDEN, state.status());
        assertFalse(state.canApply("hello!", "en", "gas"));
    }

    @Test
    void languageChangesMakeThePreviewStale() {
        var request = state.start("hello", "en", "gas");
        state.complete(request, "こんにちは", null);

        state.observe("hello", "ja");

        assertEquals(ChatInputTranslationState.Status.HIDDEN, state.status());
        assertFalse(state.canApply("hello", "ja", "gas"));
    }

    @Test
    void keepsLongResultsVisibleButDisablesApplying() {
        var request = state.start("hello", "en", "gas");
        String longResult = "a".repeat(ChatInputTranslationState.CHAT_LIMIT + 1);
        assertTrue(state.complete(request, longResult, null));

        assertEquals(longResult, state.translation());
        assertTrue(state.tooLong());
        assertFalse(state.canApply("hello", "en", "gas"));
    }

    @Test
    void rejectsCommandsAndBlankInput() {
        assertFalse(ChatInputTranslationState.canTranslate("  \t "));
        assertFalse(ChatInputTranslationState.canTranslate("  /say hello  "));
        assertTrue(ChatInputTranslationState.canTranslate("  hello  "));
        assertNull(state.start("/help", "en", "gas"));
    }

    @Test
    void movesRecentLanguageToFrontDeduplicatesAndLimitsTheList() {
        List<String> updated = ChatInputTranslationState.updateRecentLanguages(
            List.of("ja", "fr", "de", "es", "it"), "JA"
        );

        assertEquals(List.of("ja", "fr", "de", "es", "it"), updated);
        assertEquals(List.of("pt_br", "ja", "fr", "de", "es"),
            ChatInputTranslationState.updateRecentLanguages(updated, "pt-BR"));
    }
}
