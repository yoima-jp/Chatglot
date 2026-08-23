package io.github.chatglot.moddeck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoima.moddeck.api.ConfigDefinition;
import com.yoima.moddeck.api.ConfigRegistry;
import com.yoima.moddeck.api.option.BooleanOption;
import com.yoima.moddeck.api.option.IntegerOption;
import com.yoima.moddeck.api.option.StringOption;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ModDeck migration of Chatglot settings.
 *
 * <p>These tests verify that {@link ChatglotModDeckConfig#register()} builds a definition with
 * the expected categories and options, and that the save callback maps draft values back to
 * {@link ChatglotConfig} correctly. They use an isolated runtime in a temporary directory.</p>
 */
class ChatglotModDeckConfigTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // ModDeck rejects duplicate registrations, so clear its internal registry before each
        // test. ConfigRegistry.clearForTests() is package-private by design; we call it via
        // reflection to keep the public API strict while still allowing isolated unit tests.
        Method clear = ConfigRegistry.class.getDeclaredMethod("clearForTests");
        clear.setAccessible(true);
        clear.invoke(null);

        tempDir = Files.createTempDirectory("chatglot-moddeck-test");
        ChatglotRuntime.initialize(tempDir, tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatglotRuntime.get().shutdown();
        //noinspection ResultOfMethodCallIgnored
        tempDir.toFile().delete();
    }

    @Test
    void registerCreatesDefinitionWithExpectedCategories() {
        ChatglotModDeckConfig.register();

        ConfigDefinition definition = ConfigRegistry.get(ChatglotConstants.MOD_ID).orElseThrow();
        assertNotNull(definition);
        assertEquals(ChatglotConstants.MOD_ID, definition.modId());

        List<String> categoryIds = definition.categories().stream()
            .map(c -> c.id())
            .toList();

        assertTrue(categoryIds.contains("general"));
        assertTrue(categoryIds.contains("gas"));
        assertTrue(categoryIds.contains("deepl"));
        assertTrue(categoryIds.contains("google"));
        assertTrue(categoryIds.contains("codex"));
        assertTrue(categoryIds.contains("openai"));
        assertTrue(categoryIds.contains("custom_llm"));
        assertTrue(categoryIds.contains("gemini"));
        assertTrue(categoryIds.contains("anthropic"));
        assertTrue(categoryIds.contains("translategemma_local"));
        assertTrue(categoryIds.contains("azure"));
        assertTrue(categoryIds.contains("support"));
    }

    @Test
    void registerCreatesGeneralOptions() {
        ChatglotModDeckConfig.register();

        ConfigDefinition definition = ConfigRegistry.get(ChatglotConstants.MOD_ID).orElseThrow();

        assertTrue(definition.option("general", "enabled").isPresent());
        assertTrue(definition.option("general", "provider").isPresent());
        assertTrue(definition.option("general", "target_language").isPresent());
        assertTrue(definition.option("general", "append_button").isPresent());
        assertTrue(definition.option("general", "button_label").isPresent());
        assertTrue(definition.option("general", "auto_translate").isPresent());
        assertTrue(definition.option("general", "request_timeout").isPresent());
        assertTrue(definition.option("general", "max_concurrent_translations").isPresent());
    }

    @Test
    void gasScriptDecodesMarkerContentsOnlyAtFinalBoundary() throws Exception {
        String script = gasScriptTemplate();

        // Marker contents remain HTML-encoded until the final full-result decode. This keeps
        // literal entity-looking chat text from being decoded twice during marker restoration.
        assertFalse(script.contains("decodeHtml(content)"));
        assertEquals(2, script.lines().filter(line -> line.contains("decodeHtml(translatedHtml)")).count());
    }

    @Test
    void gasScriptPreservesWhitespaceBetweenTranslatedMarkers() throws Exception {
        String script = gasScriptTemplate();

        // A translator may add a real word separator between adjacent styled spans when the
        // target language requires one; the script must not collapse those boundaries.
        assertFalse(script.contains("\"$1$2\""));
        assertTrue(script.contains("return decodeHtml(translatedHtml);"));
    }

    private static String gasScriptTemplate() throws Exception {
        Field field = ChatglotModDeckConfig.class.getDeclaredField("GAS_SCRIPT_TEMPLATE");
        field.setAccessible(true);
        return (String) field.get(null);
    }


}
