package io.github.chatglot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatglotConfigTest {

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
