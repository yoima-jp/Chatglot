package io.github.chatglot.fabric.config;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.config.entry.CodexAuthButtonEntry;
import io.github.chatglot.translation.LanguageUtil;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChatglotConfigScreenFactory {
    private static final String DEEPL_API_KEYS_URL = "https://www.deepl.com/ja/your-account/keys";

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

    private record MinecraftLanguageOption(String code, String label) {
    }

    public static Screen create(Screen parent) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();
        MinecraftClient client = MinecraftClient.getInstance();

        List<MinecraftLanguageOption> languageOptions = collectLanguageOptions(client);
        MinecraftLanguageOption currentLanguageOption = resolveCurrentLanguageOption(client, languageOptions);
        MinecraftLanguageOption selectedLanguageOption = resolveSelectedLanguageOption(
            languageOptions,
            currentLanguageOption,
            config.targetLanguage
        );
        config.targetLanguage = normalizeLanguageCode(selectedLanguageOption.code());

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
            entryBuilder
                .startSelector(
                    Text.translatable("chatglot.config.target_language"),
                    languageOptions.toArray(MinecraftLanguageOption[]::new),
                    selectedLanguageOption
                )
                .setNameProvider(value -> Text.literal(value.label()))
                .setDefaultValue(currentLanguageOption)
                .setSaveConsumer(value -> config.targetLanguage = normalizeLanguageCode(value.code()))
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
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.deepl_get_api_key"),
                () -> Util.getOperatingSystem().open(DEEPL_API_KEYS_URL)
            )
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

    private static List<MinecraftLanguageOption> collectLanguageOptions(MinecraftClient client) {
        if (client == null || client.getLanguageManager() == null) {
            return List.of(new MinecraftLanguageOption("en_us", "English (US)"));
        }

        List<MinecraftLanguageOption> result = new ArrayList<>();
        for (Map.Entry<String, LanguageDefinition> entry : client.getLanguageManager().getAllLanguages().entrySet()) {
            String code = entry.getKey();
            String label = entry.getValue().getDisplayText().getString();
            result.add(new MinecraftLanguageOption(code, label));
        }
        result.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label(), right.label()));
        if (result.isEmpty()) {
            result.add(new MinecraftLanguageOption("en_us", "English (US)"));
        }
        return result;
    }

    private static MinecraftLanguageOption resolveCurrentLanguageOption(
        MinecraftClient client,
        List<MinecraftLanguageOption> options
    ) {
        if (client == null || client.getLanguageManager() == null) {
            return options.getFirst();
        }

        return findOptionByCode(options, client.getLanguageManager().getLanguage()).orElse(options.getFirst());
    }

    private static MinecraftLanguageOption resolveSelectedLanguageOption(
        List<MinecraftLanguageOption> options,
        MinecraftLanguageOption currentLanguageOption,
        String configuredTargetLanguage
    ) {
        String normalizedConfiguredCode = normalizeLanguageCode(configuredTargetLanguage);
        if (!normalizedConfiguredCode.isBlank()) {
            for (MinecraftLanguageOption option : options) {
                if (normalizeLanguageCode(option.code()).equals(normalizedConfiguredCode)) {
                    return option;
                }
            }
        }

        String normalizedConfigured = LanguageUtil.normalizeTargetLanguage(configuredTargetLanguage);
        if (!normalizedConfigured.isBlank()) {
            String normalizedCurrent = LanguageUtil.normalizeTargetLanguage(currentLanguageOption.code());
            if (normalizedConfigured.equals(normalizedCurrent)) {
                return currentLanguageOption;
            }

            for (MinecraftLanguageOption option : options) {
                if (normalizedConfigured.equals(LanguageUtil.normalizeTargetLanguage(option.code()))) {
                    return option;
                }
            }
        }

        return currentLanguageOption;
    }

    private static java.util.Optional<MinecraftLanguageOption> findOptionByCode(
        List<MinecraftLanguageOption> options,
        String languageCode
    ) {
        String normalizedCode = normalizeLanguageCode(languageCode);
        if (normalizedCode.isBlank()) {
            return java.util.Optional.empty();
        }
        for (MinecraftLanguageOption option : options) {
            if (normalizeLanguageCode(option.code()).equals(normalizedCode)) {
                return java.util.Optional.of(option);
            }
        }
        return java.util.Optional.empty();
    }

    private static String normalizeLanguageCode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }
}
