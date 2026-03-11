package io.github.chatglot.fabric.config;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.config.entry.CodexAuthButtonEntry;
import io.github.chatglot.localbackend.LocalBackendPaths;
import io.github.chatglot.localbackend.LocalBackendStatus;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.provider.codex.CodexOAuthService;
import io.github.chatglot.translation.provider.codex.CodexTokenStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.DropdownMenuBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatglotConfigScreenFactory {
    private static final String DEEPL_API_KEYS_URL = "https://www.deepl.com/ja/your-account/keys";
    private static final String GOOGLE_TRANSLATE_API_KEYS_URL = "https://console.cloud.google.com/apis/credentials";
    private static final String OPENAI_API_KEYS_URL = "https://platform.openai.com/api-keys";
    private static final String GEMINI_API_KEYS_URL = "https://aistudio.google.com/app/apikey";
    private static final String ANTHROPIC_API_KEYS_URL = "https://console.anthropic.com/settings/keys";
    private static final String AZURE_TRANSLATOR_API_KEYS_URL = "https://portal.azure.com";
    private static final String OLLAMA_DOWNLOAD_URL = "https://ollama.com/download";
    private static final String GAS_APPS_SCRIPT_HOME_URL = "https://script.google.com/home/?hl=ja&pli=1";
    private static final String GAS_SCRIPT_TEMPLATE = """
function doGet(e) {
  return handleRequest(e, "GET");
}

function doPost(e) {
  return handleRequest(e, "POST");
}

function handleRequest(e, method) {
  try {
    var params = {};

    if (method === "GET") {
      params = (e && e.parameter) ? e.parameter : {};
    } else if (method === "POST") {
      if (e && e.postData && e.postData.contents) {
        params = JSON.parse(e.postData.contents);
      }
    }

    var text = (params.text || "").toString().trim();
    var target = (params.target || "").toString().trim();
    var source = (params.source || "").toString().trim();

    if (!text) {
      return jsonResponse({
        ok: false,
        error: "missing_text",
        message: "The 'text' parameter is required."
      });
    }

    if (!target) {
      return jsonResponse({
        ok: false,
        error: "missing_target",
        message: "The 'target' parameter is required."
      });
    }

    var translatedText = LanguageApp.translate(text, source, target);

    return jsonResponse({
      ok: true,
      method: method,
      source: source || "auto",
      target: target,
      originalText: text,
      translatedText: translatedText
    });

  } catch (err) {
    return jsonResponse({
      ok: false,
      error: "internal_error",
      message: "An unexpected error occurred.",
      details: err.message
    });
  }
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
    """;
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatglotConfigScreenFactory.class);
    private static final String CODEX_TOKEN_FILENAME = "codex_tokens.json";
    private static volatile String localBackendStatusMessage = "";

    private enum ProviderOption {
        DEFAULT("default"),
        GAS("gas"),
        DEEPL("deepl"),
        GOOGLE("google"),
        CODEX("codex"),
        AZURE("azure"),
        OPENAI("openai"),
        GEMINI("gemini"),
        ANTHROPIC("anthropic"),
        TRANSLATEGEMMA_LOCAL("translategemma_local");

        private final String id;

        ProviderOption(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        private static ProviderOption fromConfigValue(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT;
            }

            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "default" -> DEFAULT;
                case "gas" -> GAS;
                case "deepl" -> DEEPL;
                case "google" -> GOOGLE;
                case "codex" -> CODEX;
                case "azure" -> AZURE;
                case "openai" -> OPENAI;
                case "gemini" -> GEMINI;
                case "anthropic" -> ANTHROPIC;
                case "translategemma_local" -> TRANSLATEGEMMA_LOCAL;
                default -> DEFAULT;
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

        List<String> selectableCodexModels = collectModelOptions(
            runtime.codexModelCatalogService().getCachedModels(),
            config.codexModel,
            ChatglotConfig.CODEX_DEFAULT_MODEL
        );
        String defaultCodexModel = ChatglotConfig.CODEX_DEFAULT_MODEL;
        String selectedCodexModel = resolveModelFromInput(config.codexModel, selectableCodexModels, defaultCodexModel);

        List<String> selectableOpenAiModels = collectModelOptions(
            runtime.openAiModelCatalogService().getCachedModels(),
            config.openaiModel,
            ChatglotConfig.OPENAI_DEFAULT_MODEL
        );
        String defaultOpenAiModel = ChatglotConfig.OPENAI_DEFAULT_MODEL;
        String selectedOpenAiModel = resolveModelFromInput(config.openaiModel, selectableOpenAiModels, defaultOpenAiModel);

        List<String> selectableGeminiModels = collectModelOptions(
            runtime.geminiModelCatalogService().getCachedModels(),
            config.geminiModel,
            ChatglotConfig.GEMINI_DEFAULT_MODEL
        );
        String defaultGeminiModel = ChatglotConfig.GEMINI_DEFAULT_MODEL;
        String selectedGeminiModel = resolveModelFromInput(config.geminiModel, selectableGeminiModels, defaultGeminiModel);

        List<String> selectableAnthropicModels = collectModelOptions(
            runtime.anthropicModelCatalogService().getCachedModels(),
            config.anthropicModel,
            ChatglotConfig.ANTHROPIC_DEFAULT_MODEL
        );
        String defaultAnthropicModel = ChatglotConfig.ANTHROPIC_DEFAULT_MODEL;
        String selectedAnthropicModel = resolveModelFromInput(
            config.anthropicModel,
            selectableAnthropicModels,
            defaultAnthropicModel
        );

        List<String> selectableTranslateGemmaModels = collectModelOptions(
            List.of("translategemma:4b", "translategemma:12b", "translategemma:27b"),
            config.localModelAlias,
            ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_ALIAS
        );
        String defaultTranslateGemmaModel = ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_ALIAS;
        String selectedTranslateGemmaModel = resolveModelFromInput(
            config.localModelAlias,
            selectableTranslateGemmaModels,
            defaultTranslateGemmaModel
        );

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
                .setDefaultValue(ChatglotConfig.DEFAULT_TRANSLATE_BUTTON_LABEL)
                .setSaveConsumer(value -> config.translateButtonLabel = value)
                .build()
        );
        general.addEntry(
            entryBuilder.startBooleanToggle(Text.translatable("chatglot.config.auto_translate"), config.autoTranslateEnabled)
                .setDefaultValue(false)
                .setSaveConsumer(value -> {
                    ProviderOption currentProvider = ProviderOption.fromConfigValue(config.provider);
                    config.autoTranslateEnabledWhenSupported = value;
                    config.autoTranslateEnabled = currentProvider != ProviderOption.DEFAULT && value;
                })
                .build()
        );
        general.addEntry(
            entryBuilder
                .startBooleanToggle(
                    Text.translatable("chatglot.config.overwrite_translation"),
                    config.overwriteOriginalWithTranslation
                )
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.overwriteOriginalWithTranslation = value)
                .build()
        );
        general.addEntry(
            entryBuilder
                .startBooleanToggle(Text.translatable("chatglot.config.show_translation_prefix"), config.showTranslationPrefix)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.showTranslationPrefix = value)
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
                .setDefaultValue(ProviderOption.DEFAULT)
                .setEnumNameProvider(option -> Text.translatable("chatglot.config.provider." + option.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> {
                    config.provider = value.id();
                    if (value == ProviderOption.DEFAULT) {
                        config.autoTranslateEnabledWhenSupported = false;
                        config.autoTranslateEnabled = false;
                    } else if (config.autoTranslateEnabledWhenSupported) {
                        config.autoTranslateEnabled = true;
                    }
                })
                .build()
        );
        general.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.provider_notice")).build());
        general.addEntry(
            entryBuilder.startIntField(Text.translatable("chatglot.config.request_timeout"), config.requestTimeoutSeconds)
                .setDefaultValue(45)
                .setSaveConsumer(value -> config.requestTimeoutSeconds = value)
                .build()
        );
        general.addEntry(
            entryBuilder
                .startIntField(Text.translatable("chatglot.config.max_concurrent_translations"), config.maxConcurrentTranslations)
                .setDefaultValue(1)
                .setSaveConsumer(value -> config.maxConcurrentTranslations = value)
                .build()
        );

        // Keep provider tabs in requested order.
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.gas"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.deepl"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.google"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.codex"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.azure"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.openai"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.gemini"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.anthropic"));
        builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.translategemma_local"));

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

        ConfigCategory google = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.google"));
        google.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.google_key"), config.googleTranslateApiKey)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.googleTranslateApiKey = value)
                .build()
        );
        google.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.google_get_api_key"),
                () -> Util.getOperatingSystem().open(GOOGLE_TRANSLATE_API_KEYS_URL)
            )
        );

        ConfigCategory gas = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.gas"));
        gas.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.gas_webapp_url"), config.gasWebAppUrl)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.gasWebAppUrl = value)
                .build()
        );
        gas.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.gas_copy_script"),
                () -> copyGasScriptTemplate(client)
            )
        );
        gas.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.gas_open_apps_script"),
                () -> Util.getOperatingSystem().open(GAS_APPS_SCRIPT_HOME_URL)
            )
        );
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step1")).build());
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step2")).build());
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step3")).build());
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step4")).build());
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step5")).build());
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step6")).build());
        gas.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.guide.gas.step7")).build());

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
                    value -> resolveModelFromInput(value, selectableCodexModels, selectedCodexModel),
                    Text::literal,
                    DropdownMenuBuilder.CellCreatorBuilder.of(Text::literal)
                )
                .setSelections(selectableCodexModels)
                .setDefaultValue(defaultCodexModel)
                .setSuggestionMode(true)
                .setSaveConsumer(value -> config.codexModel = normalizeModelValue(value))
                .build()
        );
        codex.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.codex_auth_start"),
                () -> startCodexAuthFlow(runtime, config, parent)
            )
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

        ConfigCategory openai = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.openai"));
        openai.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.openai_key"), config.openaiApiKey)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.openaiApiKey = value)
                .build()
        );
        openai.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.openai_get_api_key"),
                () -> Util.getOperatingSystem().open(OPENAI_API_KEYS_URL)
            )
        );
        openai.addEntry(
            entryBuilder
                .startDropdownMenu(
                    Text.translatable("chatglot.config.openai_model"),
                    selectedOpenAiModel,
                    value -> resolveModelFromInput(value, selectableOpenAiModels, selectedOpenAiModel),
                    Text::literal,
                    DropdownMenuBuilder.CellCreatorBuilder.of(Text::literal)
                )
                .setSelections(selectableOpenAiModels)
                .setDefaultValue(defaultOpenAiModel)
                .setSuggestionMode(true)
                .setSaveConsumer(value -> config.openaiModel = normalizeModelValue(value))
                .build()
        );
        openai.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.openai_model_refresh"),
                () -> refreshOpenAiModelList(runtime, config, parent)
            )
        );

        ConfigCategory gemini = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.gemini"));
        gemini.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.gemini_key"), config.geminiApiKey)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.geminiApiKey = value)
                .build()
        );
        gemini.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.gemini_get_api_key"),
                () -> Util.getOperatingSystem().open(GEMINI_API_KEYS_URL)
            )
        );
        gemini.addEntry(
            entryBuilder
                .startDropdownMenu(
                    Text.translatable("chatglot.config.gemini_model"),
                    selectedGeminiModel,
                    value -> resolveModelFromInput(value, selectableGeminiModels, selectedGeminiModel),
                    Text::literal,
                    DropdownMenuBuilder.CellCreatorBuilder.of(Text::literal)
                )
                .setSelections(selectableGeminiModels)
                .setDefaultValue(defaultGeminiModel)
                .setSuggestionMode(true)
                .setSaveConsumer(value -> config.geminiModel = normalizeModelValue(value))
                .build()
        );
        gemini.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.gemini_model_refresh"),
                () -> refreshGeminiModelList(runtime, config, parent)
            )
        );

        ConfigCategory anthropic = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.anthropic"));
        anthropic.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.anthropic_key"), config.anthropicApiKey)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.anthropicApiKey = value)
                .build()
        );
        anthropic.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.anthropic_get_api_key"),
                () -> Util.getOperatingSystem().open(ANTHROPIC_API_KEYS_URL)
            )
        );
        anthropic.addEntry(
            entryBuilder
                .startDropdownMenu(
                    Text.translatable("chatglot.config.anthropic_model"),
                    selectedAnthropicModel,
                    value -> resolveModelFromInput(value, selectableAnthropicModels, selectedAnthropicModel),
                    Text::literal,
                    DropdownMenuBuilder.CellCreatorBuilder.of(Text::literal)
                )
                .setSelections(selectableAnthropicModels)
                .setDefaultValue(defaultAnthropicModel)
                .setSuggestionMode(true)
                .setSaveConsumer(value -> config.anthropicModel = normalizeModelValue(value))
                .build()
        );
        anthropic.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.anthropic_model_refresh"),
                () -> refreshAnthropicModelList(runtime, config, parent)
            )
        );



        ConfigCategory localGemma = builder.getOrCreateCategory(
            Text.translatable("chatglot.config.category.provider.translategemma_local")
        );
        localGemma.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.local_backend_windows_only")).build());
        localGemma.addEntry(entryBuilder.startTextDescription(Text.translatable("chatglot.config.local_backend_setup_notice")).build());
        localGemma.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.local_backend_base_url"), config.localBackendBaseUrl)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.localBackendBaseUrl = value)
                .build()
        );
        localGemma.addEntry(
            entryBuilder.startIntField(Text.translatable("chatglot.config.local_backend_port"), config.localBackendPort)
                .setDefaultValue(ChatglotConfig.LOCAL_BACKEND_DEFAULT_PORT)
                .setSaveConsumer(value -> config.localBackendPort = value)
                .build()
        );
        localGemma.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.local_backend_shared_dir"), config.localBackendSharedDirectory)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.localBackendSharedDirectory = value)
                .build()
        );
        localGemma.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.local_backend_command"), config.localBackendCommand)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.localBackendCommand = value)
                .build()
        );
        localGemma.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.local_backend_model_path"), config.localModelPath)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.localModelPath = value)
                .build()
        );
        localGemma.addEntry(
            entryBuilder
                .startDropdownMenu(
                    Text.translatable("chatglot.config.local_backend_model_alias"),
                    selectedTranslateGemmaModel,
                    value -> resolveModelFromInput(value, selectableTranslateGemmaModels, selectedTranslateGemmaModel),
                    Text::literal,
                    DropdownMenuBuilder.CellCreatorBuilder.of(Text::literal)
                )
                .setSelections(selectableTranslateGemmaModels)
                .setDefaultValue(defaultTranslateGemmaModel)
                .setSuggestionMode(true)
                .setSaveConsumer(value -> config.localModelAlias = normalizeModelValue(value))
                .build()
        );
        String resolvedSharedDir = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory).toString();
        String resolvedBackendUrl = (config.localBackendBaseUrl == null || config.localBackendBaseUrl.isBlank())
            ? "http://127.0.0.1:" + config.localBackendPort
            : config.localBackendBaseUrl.trim();
        localGemma.addEntry(
            entryBuilder.startTextDescription(Text.translatable("chatglot.config.local_backend_resolved_url", resolvedBackendUrl)).build()
        );
        localGemma.addEntry(
            entryBuilder.startTextDescription(Text.translatable("chatglot.config.local_backend_resolved_shared_dir", resolvedSharedDir)).build()
        );
        localGemma.addEntry(
            entryBuilder.startTextDescription(
                Text.translatable(
                    "chatglot.config.local_backend_log_file",
                    LocalBackendPaths.logFile(LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory)).toString()
                )
            ).build()
        );
        localGemma.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.local_backend_setup"),
                () -> setupLocalBackend(runtime, config, parent)
            )
        );
        localGemma.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.local_backend_status"),
                () -> checkLocalBackendStatus(runtime, config, parent)
            )
        );
        localGemma.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.local_backend_download_model"),
                () -> downloadLocalModel(runtime, config, parent)
            )
        );
        if (!localBackendStatusMessage.isBlank()) {
            localGemma.addEntry(
                entryBuilder.startTextDescription(
                    Text.translatable("chatglot.config.local_backend_status_message", localBackendStatusMessage)
                ).build()
            );
        }

        ConfigCategory azure = builder.getOrCreateCategory(Text.translatable("chatglot.config.category.provider.azure"));
        azure.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.azure_key"), config.azureTranslatorApiKey)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.azureTranslatorApiKey = value)
                .build()
        );
        azure.addEntry(
            new CodexAuthButtonEntry(
                Text.translatable("chatglot.config.azure_get_api_key"),
                () -> Util.getOperatingSystem().open(AZURE_TRANSLATOR_API_KEYS_URL)
            )
        );
        azure.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.azure_region"), config.azureTranslatorRegion)
                .setDefaultValue("")
                .setSaveConsumer(value -> config.azureTranslatorRegion = value)
                .build()
        );
        azure.addEntry(
            entryBuilder.startStrField(Text.translatable("chatglot.config.azure_endpoint"), config.azureTranslatorEndpoint)
                .setDefaultValue(ChatglotConfig.AZURE_TRANSLATOR_DEFAULT_ENDPOINT)
                .setSaveConsumer(value -> config.azureTranslatorEndpoint = value)
                .build()
        );

        return builder.build();
    }

    private static List<String> collectModelOptions(List<String> cachedModels, String configuredModel, String defaultModel) {
        List<String> options = new ArrayList<>();

        String normalizedDefault = normalizeModelValue(defaultModel);
        if (!normalizedDefault.isBlank()) {
            addModelOptionUnique(options, normalizedDefault);
        }

        for (String cachedModel : cachedModels) {
            addModelOptionUnique(options, cachedModel);
        }

        String current = normalizeModelValue(configuredModel);
        if (!current.isBlank()) {
            addModelOptionUnique(options, current);
        }

        if (options.isEmpty()) {
            addModelOptionUnique(options, defaultModel);
        }

        return options;
    }

    private static void addModelOptionUnique(List<String> options, String candidate) {
        String normalized = normalizeModelValue(candidate);
        if (normalized.isBlank()) {
            return;
        }

        for (String existing : options) {
            if (existing.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        options.add(normalized);
    }

    private static String resolveModelFromInput(String input, List<String> options, String fallback) {
        String normalized = normalizeModelValue(input);
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

    private static String normalizeModelValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private static void copyGasScriptTemplate(MinecraftClient client) {
        if (client == null || client.keyboard == null) {
            LOGGER.warn("Failed to copy GAS script: Minecraft keyboard is unavailable.");
            return;
        }

        client.keyboard.setClipboard(GAS_SCRIPT_TEMPLATE);
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("chatglot.config.gas_script_copied"), false);
        }
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

    private static void startCodexAuthFlow(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("chatglot.config.codex_auth_starting"), false);
        }

        Thread authThread = new Thread(() -> {
            try {
                Path tokenFile = resolveCodexTokenFile(runtime, config);
                new CodexOAuthService().authenticateInBrowser(tokenFile);
                try {
                    if (new CodexTokenStore().read(tokenFile) != null) {
                        runtime.codexModelCatalogService().refreshModels();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Codex auth succeeded but model refresh failed: {}", e.getMessage());
                }
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(
                            Text.translatable("chatglot.config.codex_auth_start.success", tokenFile.toString()),
                            false
                        );
                    }
                    client.setScreen(create(parent));
                });
            } catch (Exception e) {
                LOGGER.warn("Failed to complete Codex OAuth flow: {}", e.getMessage());
                if (e instanceof CodexOAuthService.SupersededAuthorizationException) {
                    return;
                }
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(
                            Text.translatable("chatglot.config.codex_auth_start.failed", e.getMessage()),
                            false
                        );
                    }
                });
            }
        }, "chatglot-codex-auth");
        authThread.setDaemon(true);
        authThread.start();
    }

    private static Path resolveCodexTokenFile(ChatglotRuntime runtime, ChatglotConfig config) {
        if (config.codexTokenFile != null && !config.codexTokenFile.isBlank()) {
            return Path.of(config.codexTokenFile.trim());
        }
        return runtime.configDir().resolve(ChatglotConstants.MOD_ID).resolve(CODEX_TOKEN_FILENAME);
    }

    private static void refreshOpenAiModelList(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            List<String> refreshed = runtime.openAiModelCatalogService().refreshModels(config.openaiApiKey, config.requestTimeoutSeconds);
            LOGGER.info("Refreshed OpenAI model list from remote API. count={}", refreshed.size());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.openai_model_refresh.success", Integer.toString(refreshed.size())),
                    false
                );
            }
            client.setScreen(create(parent));
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh OpenAI model list: {}", e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.openai_model_refresh.failed", e.getMessage()),
                    false
                );
            }
        }
    }

    private static void refreshGeminiModelList(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            List<String> refreshed = runtime.geminiModelCatalogService().refreshModels(config.geminiApiKey, config.requestTimeoutSeconds);
            LOGGER.info("Refreshed Gemini model list from remote API. count={}", refreshed.size());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.gemini_model_refresh.success", Integer.toString(refreshed.size())),
                    false
                );
            }
            client.setScreen(create(parent));
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Gemini model list: {}", e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.gemini_model_refresh.failed", e.getMessage()),
                    false
                );
            }
        }
    }

    private static void refreshAnthropicModelList(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            List<String> refreshed = runtime.anthropicModelCatalogService().refreshModels(
                config.anthropicApiKey,
                config.requestTimeoutSeconds
            );
            LOGGER.info("Refreshed Anthropic model list from remote API. count={}", refreshed.size());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.anthropic_model_refresh.success", Integer.toString(refreshed.size())),
                    false
                );
            }
            client.setScreen(create(parent));
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Anthropic model list: {}", e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(
                    Text.translatable("chatglot.config.anthropic_model_refresh.failed", e.getMessage()),
                    false
                );
            }
        }
    }


    private static void setupLocalBackend(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent, () -> runtime.localBackendManager().setupAndStart(config), "setup");
    }

    private static void downloadLocalModel(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent, () -> runtime.localBackendManager().downloadModel(config), "download");
    }

    private static void checkLocalBackendStatus(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent, () -> runtime.localBackendManager().checkStatus(config), "status");
    }

    private interface LocalBackendAction {
        LocalBackendStatus run() throws Exception;
    }

    private static void runLocalBackendAction(
        ChatglotRuntime runtime,
        ChatglotConfig config,
        Screen parent,
        LocalBackendAction action,
        String actionName
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        Thread worker = new Thread(() -> {
            try {
                config.sanitize();
                runtime.configManager().save();
                LocalBackendStatus status = action.run();
                localBackendStatusMessage = status.message();
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(
                            Text.translatable("chatglot.config.local_backend_status_message", localBackendStatusMessage),
                            false
                        );
                    }
                    if (parent != null) {
                        client.setScreen(create(parent));
                    }
                });
            } catch (Exception e) {
                localBackendStatusMessage = actionName + " failed: " + e.getMessage();
                LOGGER.warn("Failed local TranslateGemma {}: {}", actionName, e.getMessage());
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(
                            Text.translatable("chatglot.config.local_backend_status_message", localBackendStatusMessage),
                            false
                        );
                    }
                    if (parent != null) {
                        client.setScreen(create(parent));
                    }
                });
            }
        }, "chatglot-local-backend-" + actionName);
        worker.setDaemon(true);
        worker.start();
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




