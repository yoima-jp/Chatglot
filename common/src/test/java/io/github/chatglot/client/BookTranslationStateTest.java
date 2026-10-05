package io.github.chatglot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.client.BookTranslationState.Key;
import io.github.chatglot.client.BookTranslationState.Request;
import io.github.chatglot.client.BookTranslationState.Status;
import org.junit.jupiter.api.Test;

class BookTranslationStateTest {
    private final BookTranslationState state = new BookTranslationState();
    private final Key firstPage = new Key("A quiet village", "JA", "gas");

    @Test
    void togglesCachedTranslationAndReusesIt() {
        Request request = state.activate(firstPage);
        assertNotNull(request);
        assertEquals(Status.LOADING, state.status());
        assertTrue(state.complete(request, "静かな村", null));
        assertEquals(Status.READY, state.status());
        assertEquals("静かな村", state.displayedTranslation());

        assertNull(state.activate(firstPage));
        assertNull(state.displayedTranslation());
        assertEquals(Status.HIDDEN, state.status());
        assertNull(state.activate(firstPage));
        assertEquals("静かな村", state.displayedTranslation());
    }

    @Test
    void pageChangesKeepDisplayModeWithoutStartingRequests() {
        Request request = state.activate(firstPage);
        state.complete(request, "静かな村", null);
        Key nextPage = new Key("A winding road", "JA", "gas");

        state.observe(nextPage);
        assertTrue(state.showingTranslations());
        assertNull(state.displayedTranslation());
        assertEquals(Status.HIDDEN, state.status());

        Request nextRequest = state.activate(nextPage);
        assertNotNull(nextRequest);
        assertEquals(nextPage, nextRequest.key());
    }

    @Test
    void cacheKeyIncludesPageTextLanguageAndProvider() {
        Request request = state.activate(firstPage);
        state.complete(request, "静かな村", null);

        for (Key changed : new Key[] {
            new Key("Another village", "JA", "gas"),
            new Key(firstPage.text(), "FR", "gas"),
            new Key(firstPage.text(), "JA", "deepl")
        }) {
            state.observe(changed);
            assertNull(state.displayedTranslation());
            assertNotNull(state.activate(changed));
        }
    }

    @Test
    void invalidationDiscardsDelayedResponses() {
        Request request = state.activate(firstPage);
        state.invalidate();

        assertFalse(state.complete(request, "遅れた結果", null));
        assertNull(state.displayedTranslation());
        assertNotNull(state.activate(firstPage));
    }

    @Test
    void storesFailureForRetryAndRejectsInvalidResults() {
        Request request = state.activate(firstPage);
        IllegalStateException error = new IllegalStateException("provider unavailable");
        assertTrue(state.complete(request, null, error));
        assertEquals(Status.FAILED, state.status());
        assertSame(error, state.failure(firstPage));

        assertNotNull(state.activate(firstPage));
        Request invalidResult = state.activate(new Key("Different", "JA", "gas"));
        assertTrue(state.complete(invalidResult, "x".repeat(4097), null));
        assertEquals(Status.FAILED, state.status());
        assertNotNull(state.failure(new Key("Different", "JA", "gas")));
    }

    @Test
    void boundsPendingRequestsAndAllowsRetryWhenCapacityReturns() {
        Request[] requests = new Request[4];
        for (int index = 0; index < requests.length; index++) {
            requests[index] = state.activate(new Key("Page " + index, "JA", "gas"));
            assertNotNull(requests[index]);
        }

        Key overflow = new Key("Overflow", "JA", "gas");
        assertNull(state.activate(overflow));
        assertEquals(Status.BUSY, state.status());
        assertTrue(state.complete(requests[0], "一", null));
        assertNotNull(state.activate(overflow));
    }
}
