package io.github.chatglot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

class ChatglotConfigTest {

    @Test
    void copyKeepsOutgoingLanguageSettingsInAnIndependentList() {
        ChatglotConfig original = new ChatglotConfig();
        original.outgoingTargetLanguage = "ja_jp";
        original.recentOutgoingLanguages = new java.util.ArrayList<>(java.util.List.of("ja_jp", "fr_fr"));

        ChatglotConfig copy = original.copy();

        assertEquals("ja_jp", copy.outgoingTargetLanguage);
        assertEquals(java.util.List.of("ja_jp", "fr_fr"), copy.recentOutgoingLanguages);
        assertNotSame(original.recentOutgoingLanguages, copy.recentOutgoingLanguages);
    }

    @Test
    void usesLowestCostCodexModelByDefault() {
        ChatglotConfig config = new ChatglotConfig();

        assertEquals("gpt-5.6-luna", config.codexModel);
    }

    @Test
    void restoresDefaultCodexModelWhenConfiguredValueIsBlank() {
        ChatglotConfig config = new ChatglotConfig();
        config.codexModel = "  ";

        config.sanitize();

        assertEquals("gpt-5.6-luna", config.codexModel);
    }

    @Test
    void usesGpt56LunaAsTheOpenAiDefaultModel() {
        ChatglotConfig config = new ChatglotConfig();

        assertEquals("gpt-5.6-luna", config.openaiModel);
    }

    @Test
    void migratesThePreviousOpenAiDefaultModel() {
        ChatglotConfig config = new ChatglotConfig();
        config.openaiModel = "gpt-5-nano";

        config.sanitize();

        assertEquals("gpt-5.6-luna", config.openaiModel);
    }
}
