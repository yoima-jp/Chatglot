package io.github.chatglot.fabric.config;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Locale;

public final class ChatglotConfigScreenFactory {
    private enum ProviderOption {
        DEEPL("deepl"),
        CODEX("codex");

        private final String id;

        ProviderOption(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        private static ProviderOption fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return DEEPL;
            }

            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "codex" -> CODEX;
                default -> DEEPL;
            };
        }
    }

    private ChatglotConfigScreenFactory() {
    }

    public static Screen create(Screen parent) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("chatglot.config.title"));

        builder.setSavingRunnable(() -> {
            config.sanitize();
            runtime.configManager().save();
        });

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.general"));
        general.addEntry(
            entryBuilder.startBooleanToggle(Text.translatable("chatglot.config.enabled"), config.enabled)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.enabled = value)
                .build()
        );
        general.addEntry(
            entryBuilder.startBooleanToggle(Text.translatable("chatglot.config.append_button"), config.appendTranslateButton)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.appendTranslateButton = value)
                .build()
        );
        general.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.button_label"), config.translateButtonLabel)
                .setDefaultValue("✍️")
                .setSaveConsumer(value -> config.translateButtonLabel = value)
                .build()
        );
        general.addEntry(
            entryBuilder.startBooleanToggle(Text.translatable("chatglot.config.auto_translate"), config.autoTranslateEnabled)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.autoTranslateEnabled = value)
                .build()
        );
        general.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.target_language"), config.targetLanguage)
                .setDefaultValue("EN")
                .setSaveConsumer(value -> config.targetLanguage = value)
                .build()
        );
        general.addEntry(
            entryBuilder.startBooleanToggle(Text.translatable("chatglot.config.show_source"), config.showSourceLanguageTag)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.showSourceLanguageTag = value)
                .build()
        );
        ProviderOption providerOption = ProviderOption.fromConfigValue(config.provider);
        general.addEntry(
            entryBuilder.startEnumSelector(Text.translatable("chatglot.config.provider"), ProviderOption.class, providerOption)
                .setDefaultValue(ProviderOption.DEEPL)
                .setEnumNameProvider(option -> Text.translatable("chatglot.config.provider." + option.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> config.provider = value.id())
                .build()
        );
        general.addEntry(
            entryBuilder.startIntField(Text.translatable("chatglot.config.request_timeout"), config.requestTimeoutSeconds)
                .setDefaultValue(45)
                .setSaveConsumer(value -> config.requestTimeoutSeconds = value)
                .build()
        );

        ConfigCategory deepL = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.deepl"));
        deepL.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.deepl_key"), config.deeplApiKey)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.deeplApiKey = value)
                .build()
        );
        deepL.addEntry(
            entryBuilder.startBooleanToggle(Text.translatable("chatglot.config.deepl_free"), config.deeplUseFreeApi)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.deeplUseFreeApi = value)
                .build()
        );

        ConfigCategory codex = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.codex"));
        codex.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_python"), config.codexPythonCommand)
                .setDefaultValue("python")
                .setSaveConsumer(value -> config.codexPythonCommand = value)
                .build()
        );
        codex.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_script"), config.codexScriptPath)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.codexScriptPath = value)
                .build()
        );
        codex.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_token"), config.codexTokenFile)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.codexTokenFile = value)
                .build()
        );
        codex.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_model"), config.codexModel)
                .setDefaultValue("gpt-5.3-codex")
                .setSaveConsumer(value -> config.codexModel = value)
                .build()
        );
        codex.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_effort"), config.codexReasoningEffort)
                .setDefaultValue("medium")
                .setSaveConsumer(value -> config.codexReasoningEffort = value)
                .build()
        );
        codex.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_summary"), config.codexReasoningSummary)
                .setDefaultValue("auto")
                .setSaveConsumer(value -> config.codexReasoningSummary = value)
                .build()
        );

        return builder.build();
    }
}
