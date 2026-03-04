package io.github.chatglot.fabric.config;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.config.entry.CodexAuthButtonEntry;
import io.github.chatglot.translation.LanguageUtil;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.DropdownMenuBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatglotConfigScreenFactory {
    private static final String DEEPL_API_KEYS_URL = "https://www.deepl.com/ja/your-account/keys";
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatglotConfigScreenFactory.class);

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

    private enum CodexReasoningOption {
        LOW(ChatglotConfig.CODEX_REASONING_EFFORT_LOW),
        MEDIUM(ChatglotConfig.CODEX_REASONING_EFFORT_MEDIUM),
        HIGH(ChatglotConfig.CODEX_REASONING_EFFORT_HIGH),
        EXTRA_HIGH(ChatglotConfig.CODEX_REASONING_EFFORT_EXTRA_HIGH);

        private final String apiValue;

        CodexReasoningOption(String apiValue) {
            this.apiValue = apiValue;
        }

        private String apiValue() {
            return apiValue;
        }

        private static CodexReasoningOption fromConfigValue(String value) {
            return switch (ChatglotConfig.normalizeCodexReasoningEffort(value)) {
                case ChatglotConfig.CODEX_REASONING_EFFORT_LOW -> LOW;
                case ChatglotConfig.CODEX_REASONING_EFFORT_HIGH -> HIGH;
                case ChatglotConfig.CODEX_REASONING_EFFORT_EXTRA_HIGH -> EXTRA_HIGH;
                default -> MEDIUM;
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
        MinecraftLanguageOption defaultLanguageOption = createDefaultLanguageOption(currentLanguageOption);
        List<MinecraftLanguageOption> selectableLanguageOptions = prependDefaultOption(defaultLanguageOption, languageOptions);
        MinecraftLanguageOption selectedLanguageOption = resolveSelectedLanguageOption(
            selectableLanguageOptions,
            currentLanguageOption,
            defaultLanguageOption,
            config.targetLanguage
        );
        List<String> selectableCodexModels = collectCodexModelOptions(runtime, config.codexModel);
        String defaultCodexModel = selectableCodexModels.getFirst();
        String selectedCodexModel = resolveCodexModelFromInput(config.codexModel, selectableCodexModels, defaultCodexModel);

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
                .startDropdownMenu(
                    Text.translatable("chatglot.config.target_language"),
                    selectedLanguageOption,
                    value -> resolveLanguageOptionFromInput(value, selectableLanguageOptions, selectedLanguageOption),
                    value -> Text.literal(value.label()),
                    DropdownMenuBuilder.CellCreatorBuilder.of(value -> Text.literal(value.label()))
                )
                .setSelections(selectableLanguageOptions)
                .setDefaultValue(defaultLanguageOption)
                .setSuggestionMode(false)
                .setSaveConsumer(value -> config.targetLanguage = toStoredTargetLanguage(value.code()))
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
            entryBuilder.startStrField(Text.translatable("chatglot.config.codex_token"), config.codexTokenFile)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.codexTokenFile = value)
                .build()
        );
        codex.addEntry(
            entryBuilder
                .startDropdownMenu(
                    Text.translatable("chatglot.config.codex_model"),
                    selectedCodexModel,
                    value -> resolveCodexModelFromInput(value, selectableCodexModels, selectedCodexModel),
                    Text::literal,
                    DropdownMenuBuilder.CellCreatorBuilder.of(Text::literal)
                )
                .setSelections(selectableCodexModels)
                .setDefaultValue(defaultCodexModel)
                .setSuggestionMode(true)
                .setSaveConsumer(value -> config.codexModel = normalizeCodexModel(value))
                .build()
        );
        codex.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.codex_model_refresh"),
                () -> refreshCodexModelList(runtime, parent)
            )
        );
        CodexReasoningOption codexReasoningOption = CodexReasoningOption.fromConfigValue(config.codexReasoningEffort);
        codex.addEntry(
            entryBuilder.startEnumSelector(
                    Text.translatable("chatglot.config.codex_effort"),
                    CodexReasoningOption.class,
                    codexReasoningOption
                )
                .setDefaultValue(CodexReasoningOption.MEDIUM)
                .setEnumNameProvider(
                    option -> Text.translatable("chatglot.config.codex_effort." + option.name().toLowerCase(Locale.ROOT))
                )
                .setSaveConsumer(value -> config.codexReasoningEffort = value.apiValue())
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

    private static List<String> collectCodexModelOptions(ChatglotRuntime runtime, String configuredModel) {
        List<String> cached = runtime.codexModelCatalogService().getCachedModels();
        List<String> options = new ArrayList<>(cached);

        String current = normalizeCodexModel(configuredModel);
        if (current.isBlank()) {
            current = "gpt-5.3-codex";
        }

        if (options.isEmpty()) {
            options.add(current);
            return options;
        }

        Set<String> known = Set.copyOf(options);
        if (!known.contains(current)) {
            options.addFirst(current);
        }
        return options;
    }

    private static String resolveCodexModelFromInput(String input, List<String> options, String fallback) {
        String normalized = normalizeCodexModel(input);
        if (normalized.isBlank()) {
            return fallback;
        }

        for (String option : options) {
            if (option.equalsIgnoreCase(normalized)) {
                return option;
            }
        }
        return normalized;
    }

    private static String normalizeCodexModel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private static void refreshCodexModelList(ChatglotRuntime runtime, Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            List<String> refreshed = runtime.codexModelCatalogService().refreshModels();
            LOGGER.info("Refreshed Codex model list from remote API. count={}", refreshed.size());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.codex_model_refresh.success", Integer.toString(refreshed.size())),
                    false
                );
            }
            client.setScreen(create(parent));
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Codex model list: {}", e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.codex_model_refresh.failed", e.getMessage()),
                    false
                );
            }
        }
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
        MinecraftLanguageOption defaultLanguageOption,
        String configuredTargetLanguage
    ) {
        if (LanguageUtil.isMinecraftDefaultTarget(configuredTargetLanguage)) {
            return defaultLanguageOption;
        }

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

    private static MinecraftLanguageOption createDefaultLanguageOption(MinecraftLanguageOption currentLanguageOption) {
        String currentLabel = currentLanguageOption.label();
        String label = Text.translatable("chatglot.config.target_language.default", currentLabel).getString();
        return new MinecraftLanguageOption(LanguageUtil.MINECRAFT_DEFAULT_TARGET, label);
    }

    private static List<MinecraftLanguageOption> prependDefaultOption(
        MinecraftLanguageOption defaultOption,
        List<MinecraftLanguageOption> languageOptions
    ) {
        List<MinecraftLanguageOption> result = new ArrayList<>(languageOptions.size() + 1);
        result.add(defaultOption);
        result.addAll(languageOptions);
        return result;
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

    private static java.util.Optional<MinecraftLanguageOption> findOptionByLabel(
        List<MinecraftLanguageOption> options,
        String label
    ) {
        if (label == null || label.isBlank()) {
            return java.util.Optional.empty();
        }

        String trimmed = label.trim();
        for (MinecraftLanguageOption option : options) {
            if (option.label().equals(trimmed)) {
                return java.util.Optional.of(option);
            }
        }
        return java.util.Optional.empty();
    }

    private static MinecraftLanguageOption resolveLanguageOptionFromInput(
        String input,
        List<MinecraftLanguageOption> options,
        MinecraftLanguageOption fallback
    ) {
        if (input == null || input.isBlank()) {
            return fallback;
        }

        return findOptionByCode(options, input)
            .or(() -> findOptionByLabel(options, input))
            .orElseGet(() -> {
                String normalized = LanguageUtil.normalizeTargetLanguage(input);
                if (normalized.isBlank()) {
                    return fallback;
                }

                for (MinecraftLanguageOption option : options) {
                    if (normalized.equals(LanguageUtil.normalizeTargetLanguage(option.code()))) {
                        return option;
                    }
                }
                return fallback;
            });
    }

    private static String normalizeLanguageCode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String toStoredTargetLanguage(String optionCode) {
        if (LanguageUtil.isMinecraftDefaultTarget(optionCode)) {
            return LanguageUtil.MINECRAFT_DEFAULT_TARGET;
        }
        return normalizeLanguageCode(optionCode);
    }
}
