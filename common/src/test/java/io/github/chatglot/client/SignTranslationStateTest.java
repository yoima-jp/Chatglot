package io.github.chatglot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chatglot.client.SignTranslationState.Key;
import io.github.chatglot.client.SignTranslationState.Status;
import io.github.chatglot.client.SignTranslationState.Target;
import org.junit.jupiter.api.Test;

class SignTranslationStateTest {
    private final SignTranslationState state = new SignTranslationState();
    private final Key key = new Key("Welcome\nto our\nvillage", "JA", "gas");
    private final Target target = new Target(1, 2, 3, true, key);

    @Test
    void deduplicatesPendingRequestsAndCachesCompletedTranslations() {
        assertTrue(state.request(target, 0));
        assertFalse(state.request(target, 1));
        state.complete(key, "村へようこそ");
        state.hide();
        assertFalse(state.request(new Target(9, 2, 3, false, key), 2));
        assertEquals(Status.READY, state.status());
        assertEquals("村へようこそ", state.translation());
    }

    @Test
    void toleratesBriefLookAwayButDoesNotReopenOnLateCompletion() {
        state.request(target, 0);
        state.observe(null, 799);
        assertEquals(Status.LOADING, state.status());
        state.observe(target, 800);
        state.observe(null, 1599);
        assertEquals(Status.LOADING, state.status());
        state.observe(null, 1600);
        state.complete(key, "村へようこそ");
        assertEquals(Status.HIDDEN, state.status());
        state.observe(target, 1601);
        assertEquals(Status.HIDDEN, state.status());
        assertFalse(state.request(target, 1602));
        assertEquals(Status.READY, state.status());
    }

    @Test
    void changingFacesImmediatelyHidesTheOldPanel() {
        state.request(target, 0);
        state.observe(new Target(1, 2, 3, false, key), 1);
        assertEquals(Status.HIDDEN, state.status());
    }

    @Test
    void oldResponseCannotReplaceAnotherSignsTranslation() {
        state.request(target, 0);
        Key otherKey = new Key("Exit", "JA", "gas");
        state.request(new Target(4, 2, 3, true, otherKey), 1);
        state.complete(otherKey, "出口");
        state.complete(key, "村へようこそ");
        assertEquals("出口", state.translation());
    }

    @Test
    void cacheDistinguishesContentLanguageAndProvider() {
        state.request(target, 0);
        state.complete(key, "村へようこそ");
        for (Key changed : new Key[] {
            new Key("Changed", "JA", "gas"),
            new Key(key.text(), "FR", "gas"),
            new Key(key.text(), "JA", "deepl")
        }) {
            assertTrue(state.request(new Target(1, 2, 3, true, changed), 1));
            state.complete(changed, "translation");
        }
    }

    @Test
    void failuresAndInvalidResultsCanBeRetried() {
        for (String result : new String[] { null, "  ", "x".repeat(4097) }) {
            assertTrue(state.request(target, 0));
            state.complete(key, result);
            assertEquals(Status.FAILED, state.status());
        }
        assertTrue(state.request(target, 1));
    }

    @Test
    void boundsPendingRequestsAndAllowsRetryWhenCapacityReturns() {
        for (int index = 0; index < 4; index++) {
            assertTrue(state.request(new Target(index, 0, 0, true, new Key("Sign " + index, "JA", "gas")), 0));
        }
        assertFalse(state.request(target, 1));
        assertEquals(Status.BUSY, state.status());
        state.complete(new Key("Sign 0", "JA", "gas"), "看板");
        assertTrue(state.request(target, 2));
    }

    @Test
    void evictsOldCacheEntries() {
        state.request(target, 0);
        state.complete(key, "村へようこそ");
        for (int index = 0; index < 128; index++) {
            Key added = new Key("Sign " + index, "JA", "gas");
            state.request(new Target(index, 0, 0, true, added), 0);
            state.complete(added, "看板");
        }
        assertTrue(state.request(target, 1));
    }

    @Test
    void ignoresEmptyTargetsAndUnsolicitedCompletions() {
        assertFalse(state.request(null, 0));
        assertFalse(state.request(new Target(0, 0, 0, true, new Key(" \n", "JA", "gas")), 0));
        state.complete(key, "村へようこそ");
        assertEquals(Status.HIDDEN, state.status());
        assertTrue(state.request(target, 1));
    }
}
