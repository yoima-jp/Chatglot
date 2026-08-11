package io.github.chatglot.moddeck;

import com.google.gson.stream.JsonReader;
import com.yoima.moddeck.api.ConfigDefinition;
import com.yoima.moddeck.api.ConfigRegistry;
import com.yoima.moddeck.api.ConfigScreenApi;
import com.yoima.moddeck.api.ConfigText;
import com.yoima.moddeck.api.option.*;
import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.ChatOutput;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotStoragePaths;
import io.github.chatglot.localbackend.LocalBackendPaths;
import io.github.chatglot.localbackend.LocalBackendStatus;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.provider.codex.CodexOAuthService;
import io.github.chatglot.translation.provider.codex.CodexTokenStore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Declarative ModDeck definition for Chatglot settings.
 *
 * <p>All dynamic behavior that used to live in the Cloth Config screen factory
 * is expressed through ModDeck option callbacks and requirements. The persistent
 * store is intentionally left to {@link ChatglotConfigManager} so existing
 * {@code config/chatglot/chatglot.json} files, shared AppData settings, and
 * sanitize/validation behavior continue to work.</p>
 */
public final class ChatglotModDeckConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatglotModDeckConfig.class);

    private static final String DEEPL_API_KEYS_URL = "https://www.deepl.com/ja/your-account/keys";
    private static final String GOOGLE_TRANSLATE_API_KEYS_URL = "https://console.cloud.google.com/apis/credentials";
    private static final String OPENAI_API_KEYS_URL = "https://platform.openai.com/api-keys";
    private static final String GEMINI_API_KEYS_URL = "https://aistudio.google.com/app/apikey";
    private static final String ANTHROPIC_API_KEYS_URL = "https://console.anthropic.com/settings/keys";
    private static final String AZURE_TRANSLATOR_API_KEYS_URL = "https://portal.azure.com";
    private static final String GAS_APPS_SCRIPT_HOME_URL = "https://script.google.com/home/?hl=ja&pli=1";
    private static final String GITHUB_ISSUES_URL = "https://github.com/yoima-jp/Chatglot/issues/new/choose";
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

    private static volatile Component localBackendStatusMessage = Component.empty();
    private static volatile String lastLocalBackendChatMessage = "";

    // ModDeck option IDs must match [a-z0-9_.-]. Keep them stable so existing
    // storage/JSON keys and presets do not move unexpectedly.
    private static final String CAT_GENERAL = "general";
    private static final String CAT_GAS = "gas";
    private static final String CAT_DEEPL = "deepl";
    private static final String CAT_GOOGLE = "google";
    private static final String CAT_CODEX = "codex";
    private static final String CAT_OPENAI = "openai";
    private static final String CAT_CUSTOM_LLM = "custom_llm";
    private static final String CAT_GEMINI = "gemini";
    private static final String CAT_ANTHROPIC = "anthropic";
    private static final String CAT_TRANSLATEGEMMA = "translategemma_local";
    private static final String CAT_AZURE = "azure";
    private static final String CAT_SUPPORT = "support";

    // Categories used only inside the advanced subcategory screen.
    private static final String CAT_ADVANCED = "advanced";

    private ChatglotModDeckConfig() {
    }

    /**
     * Registers the Chatglot ModDeck config definition.
     *
     * <p>During normal gameplay the definition is registered exactly once by
     * {@link io.github.chatglot.fabric.ChatglotFabricClient}. Test code may call this method
     * multiple times with different runtimes; the package-private
     * {@link ConfigRegistry#clearForTests()} resets the registry between runs.</p>
     */
    public static void register() {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();

        ConfigDefinition definition = buildMainDefinition(runtime, config);
        ConfigScreenApi.register(definition);
    }

    /**
     * Registers the definition once the client resources are ready.
     *
     * <p>The Minecraft language catalog is incomplete during early client initialization,
     * so callers that open a screen should use this guard after startup rather than taking
     * a language snapshot from {@code onInitializeClient()}.</p>
     */
    public static void registerIfAbsent() {
        if (ConfigRegistry.get(ChatglotConstants.MOD_ID).isEmpty()) {
            register();
        }
    }

    /** Rebuilds runtime-dependent choices immediately before opening the screen. */
    public static void refreshRegistration() {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ConfigScreenApi.registerOrReplace(buildMainDefinition(runtime, runtime.configManager().get()));
    }

    private static ConfigDefinition buildMainDefinition(ChatglotRuntime runtime, ChatglotConfig config) {
        // Collect Minecraft language choices once at build time. Choices are a
        // snapshot; if the player changes language while the screen is open,
        // closing and reopening the screen refreshes them.
        List<MinecraftLanguageOption> languageOptions = collectLanguageOptions(Minecraft.getInstance());
        LOGGER.info("Building ModDeck config with {} Minecraft language choices.", languageOptions.size());
        MinecraftLanguageOption defaultLanguageOption = createDefaultLanguageOption(
            resolveCurrentLanguageOption(Minecraft.getInstance(), languageOptions));
        List<MinecraftLanguageOption> selectableLanguageOptions = prependDefaultOption(defaultLanguageOption, languageOptions);

        List<String> selectableCodexModels = collectModelOptions(
            runtime.codexModelCatalogService().getCachedModels(),
            config.codexModel,
            ChatglotConfig.CODEX_DEFAULT_MODEL
        );
        List<String> selectableOpenAiModels = collectModelOptions(
            runtime.openAiModelCatalogService().getCachedModels(),
            config.openaiModel,
            ChatglotConfig.OPENAI_DEFAULT_MODEL
        );
        List<String> selectableGeminiModels = collectModelOptions(
            runtime.geminiModelCatalogService().getCachedModels(),
            config.geminiModel,
            ChatglotConfig.GEMINI_DEFAULT_MODEL
        );
        List<String> selectableAnthropicModels = collectModelOptions(
            runtime.anthropicModelCatalogService().getCachedModels(),
            config.anthropicModel,
            ChatglotConfig.ANTHROPIC_DEFAULT_MODEL
        );
        List<String> selectableTranslateGemmaModels = collectModelOptions(
            List.of("translategemma:4b", "translategemma:12b", "translategemma:27b"),
            config.localModelAlias,
            ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_ALIAS
        );

        // General category options
        BooleanOption enabled = booleanOption("enabled", "chatglot.config.enabled", config.enabled);
        SelectorOption<ProviderOption> provider = selectorOption(
            "provider",
            "chatglot.config.provider",
            ProviderOption.fromConfigValue(config.provider),
            List.of(ProviderOption.values()),
            new ProviderCodec(),
            value -> ConfigText.translatable("chatglot.config.provider." + value.name().toLowerCase(Locale.ROOT))
        );
        SelectorOption<MinecraftLanguageOption> targetLanguage = selectorOption(
            "target_language",
            "chatglot.config.target_language",
            resolveSelectedLanguageOption(
                selectableLanguageOptions,
                resolveCurrentLanguageOption(Minecraft.getInstance(), languageOptions),
                defaultLanguageOption,
                config.targetLanguage
            ),
            selectableLanguageOptions,
            new LanguageOptionCodec(),
            MinecraftLanguageOption::labelText
        );
        BooleanOption appendButton = booleanOption("append_button", "chatglot.config.append_button", config.appendTranslateButton);
        StringOption buttonLabel = stringOption(
            "button_label",
            "chatglot.config.button_label",
            config.translateButtonLabel,
            32
        );
        BooleanOption autoTranslate = booleanOption("auto_translate", "chatglot.config.auto_translate", config.autoTranslateEnabled);
        BooleanOption overwriteOriginal = booleanOption(
            "overwrite_translation",
            "chatglot.config.overwrite_translation",
            config.overwriteOriginalWithTranslation
        );
        BooleanOption showTranslationPrefix = booleanOption(
            "show_translation_prefix",
            "chatglot.config.show_translation_prefix",
            config.showTranslationPrefix
        );
        BooleanOption preserveSpeakerPrefix = booleanOption(
            "preserve_leading_speaker_prefix",
            "chatglot.config.preserve_leading_speaker_prefix",
            config.preserveLeadingSpeakerPrefix
        );
        BooleanOption useSharedAppData = booleanOption(
            "use_shared_appdata_settings",
            "chatglot.config.use_shared_appdata_settings",
            config.useSharedAppDataSettings
        );
        IntegerOption requestTimeout = integerOption(
            "request_timeout",
            "chatglot.config.request_timeout",
            config.requestTimeoutSeconds,
            5,
            240,
            1
        );
        IntegerOption maxConcurrent = integerOption(
            "max_concurrent_translations",
            "chatglot.config.max_concurrent_translations",
            config.maxConcurrentTranslations,
            1,
            16,
            1
        );

        // Wire provider change to the same sanitize rules used by the old Cloth screen.
        provider.onChanged(value -> {
            config.provider = value.id();
            if (value == ProviderOption.DEFAULT) {
                config.autoTranslateEnabledWhenSupported = false;
                config.autoTranslateEnabled = false;
                autoTranslate.trySetValue(false);
            } else if (config.autoTranslateEnabledWhenSupported) {
                config.autoTranslateEnabled = true;
                autoTranslate.trySetValue(true);
            }
        });

        autoTranslate.onChanged(value -> {
            ProviderOption currentProvider = provider.draftValue();
            config.autoTranslateEnabledWhenSupported = value;
            config.autoTranslateEnabled = currentProvider != ProviderOption.DEFAULT && value;
        });

        // Provider-specific options
        StringOption deeplKey = stringOption("deepl_key", "chatglot.config.deepl_key", config.deeplApiKey, 256);
        BooleanOption deeplFree = booleanOption("deepl_free", "chatglot.config.deepl_free", config.deeplUseFreeApi);
        StringOption googleKey = stringOption("google_key", "chatglot.config.google_key", config.googleTranslateApiKey, 256);
        StringOption gasUrl = stringOption("gas_webapp_url", "chatglot.config.gas_webapp_url", config.gasWebAppUrl, 512);
        StringOption codexTokenFile = stringOption("codex_token", "chatglot.config.codex_token", config.codexTokenFile, 512);
        SelectorOption<String> codexModel = selectorOption(
            "codex_model",
            "chatglot.config.codex_model",
            resolveModelFromInput(config.codexModel, selectableCodexModels, ChatglotConfig.CODEX_DEFAULT_MODEL),
            selectableCodexModels,
            stringCodec(),
            ConfigText::literal
        );
        SelectorOption<CodexReasoningOption> codexEffort = selectorOption(
            "codex_effort",
            "chatglot.config.codex_effort",
            CodexReasoningOption.fromConfigValue(config.codexReasoningEffort),
            List.of(CodexReasoningOption.values()),
            new EnumCodec<>(CodexReasoningOption.class),
            value -> ConfigText.translatable("chatglot.config.codex_effort." + value.name().toLowerCase(Locale.ROOT))
        );
        StringOption codexSummary = stringOption("codex_summary", "chatglot.config.codex_summary", config.codexReasoningSummary, 64);
        StringOption openaiKey = stringOption("openai_key", "chatglot.config.openai_key", config.openaiApiKey, 256);
        SelectorOption<String> openaiModel = selectorOption(
            "openai_model",
            "chatglot.config.openai_model",
            resolveModelFromInput(config.openaiModel, selectableOpenAiModels, ChatglotConfig.OPENAI_DEFAULT_MODEL),
            selectableOpenAiModels,
            stringCodec(),
            ConfigText::literal
        );
        StringOption customBaseUrl = stringOption(
            "openai_compatible_base_url",
            "chatglot.config.openai_compatible_base_url",
            config.openaiCompatibleBaseUrl,
            512
        );
        SelectorOption<OpenAiCompatibleProtocolOption> customProtocol = selectorOption(
            "openai_compatible_protocol",
            "chatglot.config.openai_compatible_protocol",
            OpenAiCompatibleProtocolOption.fromConfigValue(config.openaiCompatibleProtocol),
            List.of(OpenAiCompatibleProtocolOption.values()),
            new EnumCodec<>(OpenAiCompatibleProtocolOption.class),
            value -> ConfigText.translatable("chatglot.config.openai_compatible_protocol." + value.name().toLowerCase(Locale.ROOT))
        );
        StringOption customKey = stringOption("openai_compatible_key", "chatglot.config.openai_compatible_key", config.openaiCompatibleApiKey, 256);
        StringOption customModel = stringOption("openai_compatible_model", "chatglot.config.openai_compatible_model", config.openaiCompatibleModel, 128);
        StringOption geminiKey = stringOption("gemini_key", "chatglot.config.gemini_key", config.geminiApiKey, 256);
        SelectorOption<String> geminiModel = selectorOption(
            "gemini_model",
            "chatglot.config.gemini_model",
            resolveModelFromInput(config.geminiModel, selectableGeminiModels, ChatglotConfig.GEMINI_DEFAULT_MODEL),
            selectableGeminiModels,
            stringCodec(),
            ConfigText::literal
        );
        StringOption anthropicKey = stringOption("anthropic_key", "chatglot.config.anthropic_key", config.anthropicApiKey, 256);
        SelectorOption<String> anthropicModel = selectorOption(
            "anthropic_model",
            "chatglot.config.anthropic_model",
            resolveModelFromInput(config.anthropicModel, selectableAnthropicModels, ChatglotConfig.ANTHROPIC_DEFAULT_MODEL),
            selectableAnthropicModels,
            stringCodec(),
            ConfigText::literal
        );
        StringOption azureKey = stringOption("azure_key", "chatglot.config.azure_key", config.azureTranslatorApiKey, 256);
        StringOption azureRegion = stringOption("azure_region", "chatglot.config.azure_region", config.azureTranslatorRegion, 64);
        StringOption azureEndpoint = stringOption("azure_endpoint", "chatglot.config.azure_endpoint", config.azureTranslatorEndpoint, 512);

        // TranslateGemma local backend options
        StringOption localBaseUrl = stringOption("local_backend_base_url", "chatglot.config.local_backend_base_url", config.localBackendBaseUrl, 512);
        IntegerOption localPort = integerOption(
            "local_backend_port",
            "chatglot.config.local_backend_port",
            config.localBackendPort,
            1024,
            65535,
            1
        );
        StringOption localSharedDir = stringOption("local_backend_shared_dir", "chatglot.config.local_backend_shared_dir", config.localBackendSharedDirectory, 512);
        StringOption localCommand = stringOption("local_backend_command", "chatglot.config.local_backend_command", config.localBackendCommand, 512);
        StringOption localModelPath = stringOption("local_backend_model_path", "chatglot.config.local_backend_model_path", config.localModelPath, 512);
        SelectorOption<String> localModelAlias = selectorOption(
            "local_backend_model_alias",
            "chatglot.config.local_backend_model_alias",
            resolveModelFromInput(config.localModelAlias, selectableTranslateGemmaModels, ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_ALIAS),
            selectableTranslateGemmaModels,
            stringCodec(),
            ConfigText::literal
        );

        return ConfigDefinition.builder(ChatglotConstants.MOD_ID)
            // The Mod Deck context already communicates that this is a settings screen. Keep the
            // header to the product name and omit a description that would repeat nearby UI.
            .title("Chatglot")
            .onSave(() -> save(runtime, config))
            .categoryKey(CAT_GENERAL, "chatglot.config.category.general")
            .addOption(enabled)
            .addOption(provider)
            .descriptionEntry("provider_notice", ConfigText.translatable("chatglot.config.provider_notice"))
            .addOption(targetLanguage)
            .addOption(appendButton)
            .addOption(buttonLabel)
            .addOption(autoTranslate)
            .addOption(overwriteOriginal)
            .addOption(showTranslationPrefix)
            .addOption(preserveSpeakerPrefix)
            .addOption(useSharedAppData)
            .addOption(requestTimeout)
            .addOption(maxConcurrent)
            .categoryKey(CAT_GAS, "chatglot.config.category.provider.gas")
            .addOption(gasUrl)
            .buttonOption("gas_copy_script", ConfigText.translatable("chatglot.config.gas_copy_script"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.gas_copy_script"),
                () -> copyGasScriptTemplate(Minecraft.getInstance()))
            .buttonOption("gas_open_apps_script", ConfigText.translatable("chatglot.config.gas_open_apps_script"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.gas_open_apps_script"),
                () -> Util.getPlatform().openUri(GAS_APPS_SCRIPT_HOME_URL))
            .descriptionEntry("gas_step1", ConfigText.translatable("chatglot.config.guide.gas.step1"))
            .descriptionEntry("gas_step2", ConfigText.translatable("chatglot.config.guide.gas.step2"))
            .descriptionEntry("gas_step3", ConfigText.translatable("chatglot.config.guide.gas.step3"))
            .descriptionEntry("gas_step4", ConfigText.translatable("chatglot.config.guide.gas.step4"))
            .descriptionEntry("gas_step5", ConfigText.translatable("chatglot.config.guide.gas.step5"))
            .descriptionEntry("gas_step6", ConfigText.translatable("chatglot.config.guide.gas.step6"))
            .descriptionEntry("gas_step7", ConfigText.translatable("chatglot.config.guide.gas.step7"))
            .categoryKey(CAT_DEEPL, "chatglot.config.category.provider.deepl")
            .addOption(deeplKey)
            .buttonOption("deepl_get_api_key", ConfigText.translatable("chatglot.config.deepl_get_api_key"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.deepl_get_api_key"),
                () -> Util.getPlatform().openUri(DEEPL_API_KEYS_URL))
            .addOption(deeplFree)
            .categoryKey(CAT_GOOGLE, "chatglot.config.category.provider.google")
            .addOption(googleKey)
            .buttonOption("google_get_api_key", ConfigText.translatable("chatglot.config.google_get_api_key"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.google_get_api_key"),
                () -> Util.getPlatform().openUri(GOOGLE_TRANSLATE_API_KEYS_URL))
            .categoryKey(CAT_CODEX, "chatglot.config.category.provider.codex")
            .addOption(codexTokenFile)
            .addOption(codexModel)
            .buttonOption("codex_auth_start", ConfigText.translatable("chatglot.config.codex_auth_start"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.codex_auth_start"),
                () -> startCodexAuthFlow(runtime, config, currentScreen()))
            .buttonOption("codex_model_refresh", ConfigText.translatable("chatglot.config.codex_model_refresh"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.codex_model_refresh"),
                () -> refreshCodexModelList(runtime, currentScreen()))
            .addOption(codexEffort)
            .addOption(codexSummary)
            .categoryKey(CAT_OPENAI, "chatglot.config.category.provider.openai")
            .addOption(openaiKey)
            .buttonOption("openai_get_api_key", ConfigText.translatable("chatglot.config.openai_get_api_key"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.openai_get_api_key"),
                () -> Util.getPlatform().openUri(OPENAI_API_KEYS_URL))
            .addOption(openaiModel)
            .buttonOption("openai_model_refresh", ConfigText.translatable("chatglot.config.openai_model_refresh"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.openai_model_refresh"),
                () -> refreshOpenAiModelList(runtime, config, currentScreen()))
            .categoryKey(CAT_CUSTOM_LLM, "chatglot.config.category.provider.custom_llm")
            .addOption(customBaseUrl)
            .addOption(customProtocol)
            .addOption(customKey)
            .addOption(customModel)
            .categoryKey(CAT_GEMINI, "chatglot.config.category.provider.gemini")
            .addOption(geminiKey)
            .buttonOption("gemini_get_api_key", ConfigText.translatable("chatglot.config.gemini_get_api_key"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.gemini_get_api_key"),
                () -> Util.getPlatform().openUri(GEMINI_API_KEYS_URL))
            .addOption(geminiModel)
            .buttonOption("gemini_model_refresh", ConfigText.translatable("chatglot.config.gemini_model_refresh"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.gemini_model_refresh"),
                () -> refreshGeminiModelList(runtime, config, currentScreen()))
            .categoryKey(CAT_ANTHROPIC, "chatglot.config.category.provider.anthropic")
            .addOption(anthropicKey)
            .buttonOption("anthropic_get_api_key", ConfigText.translatable("chatglot.config.anthropic_get_api_key"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.anthropic_get_api_key"),
                () -> Util.getPlatform().openUri(ANTHROPIC_API_KEYS_URL))
            .addOption(anthropicModel)
            .buttonOption("anthropic_model_refresh", ConfigText.translatable("chatglot.config.anthropic_model_refresh"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.anthropic_model_refresh"),
                () -> refreshAnthropicModelList(runtime, config, currentScreen()))
            .categoryKey(CAT_TRANSLATEGEMMA, "chatglot.config.category.provider.translategemma_local")
            .buttonOption("local_backend_download_all", ConfigText.translatable("chatglot.config.local_backend_download_all"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_download_all"),
                () -> downloadAllAndStartLocalBackend(runtime, config, currentScreen()))
            .buttonOption("local_backend_advanced", ConfigText.translatable("chatglot.config.local_backend_advanced"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_advanced"),
                () -> openAdvancedScreen(runtime, config, currentScreen()))
            .descriptionEntry("local_backend_windows_only", ConfigText.translatable("chatglot.config.local_backend_windows_only"))
            .descriptionEntry("local_backend_setup_notice", ConfigText.translatable("chatglot.config.local_backend_setup_notice"))
            .descriptionEntry("local_backend_save_notice", ConfigText.translatable("chatglot.config.local_backend_save_notice"))
            .addOption(localBaseUrl)
            .addOption(localPort)
            .addOption(localSharedDir)
            .addOption(localCommand)
            .addOption(localModelPath)
            .addOption(localModelAlias)
            .descriptionEntry("local_backend_resolved_url", resolvedUrlText(config))
            .descriptionEntry("local_backend_resolved_shared_dir", resolvedSharedDirText(runtime, config))
            .descriptionEntry("local_backend_resolved_model_path", resolvedModelPathText(runtime, config))
            .descriptionEntry("local_backend_log_file", resolvedLogFileText(runtime, config))
            .categoryKey(CAT_AZURE, "chatglot.config.category.provider.azure")
            .addOption(azureKey)
            .buttonOption("azure_get_api_key", ConfigText.translatable("chatglot.config.azure_get_api_key"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.azure_get_api_key"),
                () -> Util.getPlatform().openUri(AZURE_TRANSLATOR_API_KEYS_URL))
            .addOption(azureRegion)
            .addOption(azureEndpoint)
            .categoryKey(CAT_SUPPORT, "chatglot.config.category.support")
            .descriptionEntry("support_issue_notice", ConfigText.translatable("chatglot.config.support.issue_notice"))
            .buttonOption("support_report_issue", ConfigText.translatable("chatglot.config.support.report_issue"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.support.report_issue"),
                () -> Util.getPlatform().openUri(GITHUB_ISSUES_URL))
            .build();
    }

    private static ConfigDefinition buildAdvancedDefinition(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        return ConfigDefinition.builder(ChatglotConstants.MOD_ID + ".advanced")
            .titleKey("chatglot.config.local_backend_advanced")
            .descriptionKey("chatglot.config.local_backend_advanced_notice")
            .categoryKey(CAT_ADVANCED, "chatglot.config.category.provider.translategemma_local")
            .buttonOption("local_backend_setup", ConfigText.translatable("chatglot.config.local_backend_setup"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_setup"),
                () -> setupLocalBackend(runtime, config, parent))
            .buttonOption("local_backend_download_model", ConfigText.translatable("chatglot.config.local_backend_download_model"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_download_model"),
                () -> downloadLocalModel(runtime, config, parent))
            .buttonOption("local_backend_reinstall_model", ConfigText.translatable("chatglot.config.local_backend_reinstall_model"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_reinstall_model"),
                () -> reinstallLocalModel(runtime, config, parent))
            .buttonOption("local_backend_status", ConfigText.translatable("chatglot.config.local_backend_status"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_status"),
                () -> checkLocalBackendStatus(runtime, config, parent))
            .buttonOption("local_backend_open_log", ConfigText.translatable("chatglot.config.local_backend_open_log"),
                ConfigText.empty(), ConfigText.translatable("chatglot.config.local_backend_open_log"),
                () -> openLocalBackendLog(config))
            .descriptionEntry("local_backend_advanced_notice", ConfigText.translatable("chatglot.config.local_backend_advanced_notice"))
            .build();
    }

    private static void openAdvancedScreen(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        ConfigDefinition advanced = buildAdvancedDefinition(runtime, config, parent);
        // The advanced screen reuses the same category IDs; register under a secondary mod ID
        // so it appears as an independent screen while still being reachable from buttons.
        ConfigScreenApi.register(advanced);
        // com.yoima.moddeck.api.ModDeckApi lives in ModDeck's client source set and is only
        // available in the fabric module. Delegate screen navigation through the fabric module
        // so the common module can stay platform/screen-agnostic.
        ChatglotModDeckScreenOpener.open(advanced.modId(), parent);
    }

    private static void save(ChatglotRuntime runtime, ChatglotConfig config) {
        // Commit all draft values back to the ChatglotConfig instance before the
        // existing persistence/validation layer takes over.
        ConfigDefinition definition = ConfigRegistry.get(ChatglotConstants.MOD_ID)
            .orElseThrow(() -> new IllegalStateException("Chatglot config definition is not registered"));

        // Map draft values back to the config object. Because the definition was
        // constructed from the live config object, we walk the options directly.
        for (com.yoima.moddeck.api.ConfigCategory category : definition.categories()) {
            for (ConfigOption<?> option : category.options()) {
                applyOptionToConfig(option, config);
            }
        }

        config.sanitize();
        runtime.configManager().save();
        runtime.localBackendManager().applyConfiguredBackendPolicyAsync(config);
    }

    private static void applyOptionToConfig(ConfigOption<?> option, ChatglotConfig config) {
        Object draft = option.draftValue();
        switch (option.id()) {
            case "enabled" -> config.enabled = (Boolean) draft;
            case "provider" -> config.provider = ((ProviderOption) draft).id();
            case "target_language" -> config.targetLanguage = toStoredTargetLanguage(((MinecraftLanguageOption) draft).code());
            case "append_button" -> config.appendTranslateButton = (Boolean) draft;
            case "button_label" -> config.translateButtonLabel = (String) draft;
            case "auto_translate" -> config.autoTranslateEnabled = (Boolean) draft;
            case "overwrite_translation" -> config.overwriteOriginalWithTranslation = (Boolean) draft;
            case "show_translation_prefix" -> config.showTranslationPrefix = (Boolean) draft;
            case "preserve_leading_speaker_prefix" -> config.preserveLeadingSpeakerPrefix = (Boolean) draft;
            case "use_shared_appdata_settings" -> config.useSharedAppDataSettings = (Boolean) draft;
            case "request_timeout" -> config.requestTimeoutSeconds = (Integer) draft;
            case "max_concurrent_translations" -> config.maxConcurrentTranslations = (Integer) draft;
            case "deepl_key" -> config.deeplApiKey = (String) draft;
            case "deepl_free" -> config.deeplUseFreeApi = (Boolean) draft;
            case "google_key" -> config.googleTranslateApiKey = (String) draft;
            case "gas_webapp_url" -> config.gasWebAppUrl = (String) draft;
            case "codex_token" -> config.codexTokenFile = (String) draft;
            case "codex_model" -> config.codexModel = normalizeModelValue((String) draft);
            case "codex_effort" -> config.codexReasoningEffort = ((CodexReasoningOption) draft).apiValue();
            case "codex_summary" -> config.codexReasoningSummary = (String) draft;
            case "openai_key" -> config.openaiApiKey = (String) draft;
            case "openai_model" -> config.openaiModel = normalizeModelValue((String) draft);
            case "openai_compatible_base_url" -> config.openaiCompatibleBaseUrl = (String) draft;
            case "openai_compatible_protocol" -> config.openaiCompatibleProtocol = ((OpenAiCompatibleProtocolOption) draft).id();
            case "openai_compatible_key" -> config.openaiCompatibleApiKey = (String) draft;
            case "openai_compatible_model" -> config.openaiCompatibleModel = (String) draft;
            case "gemini_key" -> config.geminiApiKey = (String) draft;
            case "gemini_model" -> config.geminiModel = normalizeModelValue((String) draft);
            case "anthropic_key" -> config.anthropicApiKey = (String) draft;
            case "anthropic_model" -> config.anthropicModel = normalizeModelValue((String) draft);
            case "azure_key" -> config.azureTranslatorApiKey = (String) draft;
            case "azure_region" -> config.azureTranslatorRegion = (String) draft;
            case "azure_endpoint" -> config.azureTranslatorEndpoint = (String) draft;
            case "local_backend_base_url" -> config.localBackendBaseUrl = (String) draft;
            case "local_backend_port" -> config.localBackendPort = (Integer) draft;
            case "local_backend_shared_dir" -> config.localBackendSharedDirectory = (String) draft;
            case "local_backend_command" -> config.localBackendCommand = (String) draft;
            case "local_backend_model_path" -> config.localModelPath = (String) draft;
            case "local_backend_model_alias" -> config.localModelAlias = normalizeModelValue((String) draft);
            default -> {
                // Non-persistent entries and action buttons do not need mapping.
            }
        }
    }

    private static void startCodexAuthFlow(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        Minecraft client = Minecraft.getInstance();
        ChatOutput.post(Component.translatable("chatglot.config.codex_auth_starting"));

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
                    ChatOutput.post(Component.translatable("chatglot.config.codex_auth_start.success", tokenFile.toString()));
                    refreshScreen(parent);
                });
            } catch (Exception e) {
                LOGGER.warn("Failed to complete Codex OAuth flow: {}", e.getMessage());
                if (e instanceof CodexOAuthService.SupersededAuthorizationException) {
                    return;
                }
                client.execute(() -> {
                    ChatOutput.post(Component.translatable("chatglot.config.codex_auth_start.failed", e.getMessage()));
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
        return ChatglotStoragePaths.resolveDefaultCodexTokenFile(config, runtime.configDir());
    }

    private static void refreshCodexModelList(ChatglotRuntime runtime, Screen parent) {
        Minecraft client = Minecraft.getInstance();
        try {
            List<String> refreshed = runtime.codexModelCatalogService().refreshModels();
            LOGGER.info("Refreshed Codex model list from remote API. count={}", refreshed.size());
            ChatOutput.post(Component.translatable("chatglot.config.codex_model_refresh.success", Integer.toString(refreshed.size())));
            refreshScreen(parent);
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Codex model list: {}", e.getMessage());
            ChatOutput.post(Component.translatable("chatglot.config.codex_model_refresh.failed", e.getMessage()));
        }
    }

    private static void refreshOpenAiModelList(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        Minecraft client = Minecraft.getInstance();
        try {
            List<String> refreshed = runtime.openAiModelCatalogService().refreshModels(config.openaiApiKey, config.requestTimeoutSeconds);
            LOGGER.info("Refreshed OpenAI model list from remote API. count={}", refreshed.size());
            ChatOutput.post(Component.translatable("chatglot.config.openai_model_refresh.success", Integer.toString(refreshed.size())));
            refreshScreen(parent);
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh OpenAI model list: {}", e.getMessage());
            ChatOutput.post(Component.translatable("chatglot.config.openai_model_refresh.failed", e.getMessage()));
        }
    }

    private static void refreshGeminiModelList(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        Minecraft client = Minecraft.getInstance();
        try {
            List<String> refreshed = runtime.geminiModelCatalogService().refreshModels(config.geminiApiKey, config.requestTimeoutSeconds);
            LOGGER.info("Refreshed Gemini model list from remote API. count={}", refreshed.size());
            ChatOutput.post(Component.translatable("chatglot.config.gemini_model_refresh.success", Integer.toString(refreshed.size())));
            refreshScreen(parent);
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Gemini model list: {}", e.getMessage());
            ChatOutput.post(Component.translatable("chatglot.config.gemini_model_refresh.failed", e.getMessage()));
        }
    }

    private static void refreshAnthropicModelList(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        Minecraft client = Minecraft.getInstance();
        try {
            List<String> refreshed = runtime.anthropicModelCatalogService().refreshModels(
                config.anthropicApiKey,
                config.requestTimeoutSeconds
            );
            LOGGER.info("Refreshed Anthropic model list from remote API. count={}", refreshed.size());
            ChatOutput.post(Component.translatable("chatglot.config.anthropic_model_refresh.success", Integer.toString(refreshed.size())));
            refreshScreen(parent);
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Anthropic model list: {}", e.getMessage());
            ChatOutput.post(Component.translatable("chatglot.config.anthropic_model_refresh.failed", e.getMessage()));
        }
    }

    private static void setupLocalBackend(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent,
            progress -> runtime.localBackendManager().downloadRuntime(config, progress), "setup", true);
    }

    private static void downloadAllAndStartLocalBackend(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent,
            progress -> runtime.localBackendManager().downloadAllAndStart(config, progress), "download-all", true);
    }

    private static void downloadLocalModel(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent,
            progress -> runtime.localBackendManager().downloadModel(config, progress), "download", true);
    }

    private static void reinstallLocalModel(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent,
            progress -> runtime.localBackendManager().reinstallModel(config, progress), "reinstall", true);
    }

    private static void checkLocalBackendStatus(ChatglotRuntime runtime, ChatglotConfig config, Screen parent) {
        runLocalBackendAction(runtime, config, parent,
            progress -> runtime.localBackendManager().checkStatus(config), "status", false);
    }

    private static void openLocalBackendLog(ChatglotConfig config) {
        try {
            Path logFile = LocalBackendPaths.logFile(LocalBackendPaths.resolveSharedRoot(config, ChatglotRuntime.get().configDir()));
            java.nio.file.Files.createDirectories(logFile.getParent());
            Util.getPlatform().openUri(logFile.toUri());
        } catch (Exception e) {
            LOGGER.warn("Failed to open local backend log: {}", e.getMessage());
            ChatOutput.post(
                Component.translatable("chatglot.config.local_backend_status_message",
                    Component.literal("Failed to open log: " + e.getMessage())));
        }
    }

    private interface LocalBackendAction {
        LocalBackendStatus run(java.util.function.Consumer<Component> progressListener) throws Exception;
    }

    private static void runLocalBackendAction(
        ChatglotRuntime runtime,
        ChatglotConfig config,
        Screen parent,
        LocalBackendAction action,
        String actionName,
        boolean suspendAutoTranslate
    ) {
        Minecraft client = Minecraft.getInstance();
        updateLocalBackendProgress(client, parent, Component.translatable("chatglot.local_backend.action_started", actionName), true);
        Thread worker = new Thread(() -> {
            java.util.function.Consumer<Component> progressListener = message ->
                updateLocalBackendProgress(client, parent, message, true);
            boolean previousAutoTranslateEnabled = config.autoTranslateEnabled;
            boolean previousAutoTranslateEnabledWhenSupported = config.autoTranslateEnabledWhenSupported;
            try {
                if (suspendAutoTranslate) {
                    config.autoTranslateEnabled = false;
                    config.autoTranslateEnabledWhenSupported = false;
                    runtime.configManager().save();
                    progressListener.accept(io.github.chatglot.localbackend.LocalBackendTexts.autoTranslateTemporarilyDisabled());
                }
                config.sanitize();
                runtime.configManager().save();
                LocalBackendStatus status = action.run(progressListener);
                localBackendStatusMessage = status.message();
                if (suspendAutoTranslate) {
                    restoreAutoTranslate(runtime, config, previousAutoTranslateEnabled, previousAutoTranslateEnabledWhenSupported);
                }
                client.execute(() -> {
                    ChatOutput.post(Component.translatable("chatglot.config.local_backend_status_message", localBackendStatusMessage));
                    refreshScreen(parent);
                });
            } catch (Exception e) {
                if (suspendAutoTranslate) {
                    restoreAutoTranslate(runtime, config, previousAutoTranslateEnabled, previousAutoTranslateEnabledWhenSupported);
                }
                localBackendStatusMessage = Component.translatable("chatglot.local_backend.action_failed", actionName, e.getMessage());
                LOGGER.warn("Failed local TranslateGemma {}: {}", actionName, e.getMessage());
                client.execute(() -> {
                    ChatOutput.post(Component.translatable("chatglot.config.local_backend_status_message", localBackendStatusMessage));
                    refreshScreen(parent);
                });
            }
        }, "chatglot-local-backend-" + actionName);
        worker.setDaemon(true);
        worker.start();
    }

    private static void restoreAutoTranslate(
        ChatglotRuntime runtime,
        ChatglotConfig config,
        boolean previousAutoTranslateEnabled,
        boolean previousAutoTranslateEnabledWhenSupported
    ) {
        config.autoTranslateEnabled = previousAutoTranslateEnabled;
        config.autoTranslateEnabledWhenSupported = previousAutoTranslateEnabledWhenSupported;
        config.sanitize();
        runtime.configManager().save();
    }

    private static void updateLocalBackendProgress(Minecraft client, Screen parent, Component message, boolean sendChatMessage) {
        localBackendStatusMessage = message;
        if (client == null) {
            return;
        }
        client.execute(() -> {
            String plain = message.getString();
            if (sendChatMessage && client.player != null && !plain.equals(lastLocalBackendChatMessage)) {
                lastLocalBackendChatMessage = plain;
                ChatOutput.post(localBackendStatusMessage);
            }
            refreshScreen(parent);
        });
    }

    private static Screen currentScreen() {
        Minecraft client = Minecraft.getInstance();
        return client != null ? client.gui.screen() : null;
    }

    private static void refreshScreen(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) {
            return;
        }
        // Recreate the screen so dynamic model lists and status text are rebuilt.
        // ModDeckApi is client-only in the substituted ModDeck project, so navigation is
        // delegated to the fabric module while the common module keeps the definitions.
        ChatglotModDeckScreenOpener.open(ChatglotConstants.MOD_ID, parent);
    }

    private static void copyGasScriptTemplate(Minecraft client) {
        if (client == null || client.keyboardHandler == null) {
            LOGGER.warn("Failed to copy GAS script: Minecraft keyboard is unavailable.");
            return;
        }
        client.keyboardHandler.setClipboard(GAS_SCRIPT_TEMPLATE);
        ChatOutput.post(Component.translatable("chatglot.config.gas_script_copied"));
    }

    private static List<String> collectModelOptions(List<String> cachedModels, String configuredModel, String defaultModel) {
        List<String> options = new ArrayList<>();
        addModelOptionUnique(options, defaultModel);
        for (String cachedModel : cachedModels) {
            addModelOptionUnique(options, cachedModel);
        }
        addModelOptionUnique(options, configuredModel);
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

    private static List<MinecraftLanguageOption> collectLanguageOptions(Minecraft client) {
        if (client == null || client.getLanguageManager() == null) {
            return List.of(new MinecraftLanguageOption("en_us", ConfigText.literal("English (US)")));
        }
        List<MinecraftLanguageOption> result = new ArrayList<>();
        for (Map.Entry<String, LanguageInfo> entry : client.getLanguageManager().getLanguages().entrySet()) {
            String code = entry.getKey();
            String label = entry.getValue().toComponent().getString();
            result.add(new MinecraftLanguageOption(code, ConfigText.literal(label)));
        }
        // Minecraft 26.2 can expose only en_us through LanguageManager even though the
        // downloaded asset pack contains every language. Read the same native name/region
        // fields from those language resources instead of shipping a stale hard-coded list.
        if (result.size() <= 1) {
            collectLanguageResourceOptions(client).forEach(option -> {
                if (findOptionByCode(result, option.code()).isEmpty()) {
                    result.add(option);
                }
            });
        }
        result.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label().value(), right.label().value()));
        if (result.isEmpty()) {
            result.add(new MinecraftLanguageOption("en_us", ConfigText.literal("English (US)")));
        }
        return result;
    }

    private static List<MinecraftLanguageOption> collectLanguageResourceOptions(Minecraft client) {
        List<MinecraftLanguageOption> result = new ArrayList<>();
        Map<Identifier, Resource> resources = client.getResourceManager().listResources(
            "lang",
            id -> id.getNamespace().equals("minecraft") && id.getPath().endsWith(".json")
        );
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            String path = entry.getKey().getPath();
            String code = path.substring("lang/".length(), path.length() - ".json".length());
            readLanguageLabel(entry.getValue()).ifPresent(label ->
                result.add(new MinecraftLanguageOption(code, ConfigText.literal(label))));
        }
        LOGGER.info("Discovered {} Minecraft language resources as a fallback.", result.size());
        return result;
    }

    private static Optional<String> readLanguageLabel(Resource resource) {
        String name = null;
        String region = null;
        try (JsonReader reader = new JsonReader(resource.openAsReader())) {
            reader.beginObject();
            while (reader.hasNext() && (name == null || region == null)) {
                String key = reader.nextName();
                if ("language.name".equals(key)) {
                    name = reader.nextString();
                } else if ("language.region".equals(key)) {
                    region = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
        } catch (IOException | IllegalStateException exception) {
            LOGGER.warn("Could not read Minecraft language metadata from {}.", resource.sourcePackId(), exception);
            return Optional.empty();
        }
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(region == null || region.isBlank() ? name : name + " (" + region + ")");
    }

    private static MinecraftLanguageOption resolveCurrentLanguageOption(Minecraft client, List<MinecraftLanguageOption> options) {
        if (client == null || client.getLanguageManager() == null) {
            return options.getFirst();
        }
        return findOptionByCode(options, client.getLanguageManager().getSelected()).orElse(options.getFirst());
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
        return new MinecraftLanguageOption(
            LanguageUtil.MINECRAFT_DEFAULT_TARGET,
            ConfigText.translatable("chatglot.config.target_language.default", currentLanguageOption.label().component())
        );
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

    private static Optional<MinecraftLanguageOption> findOptionByCode(List<MinecraftLanguageOption> options, String languageCode) {
        String normalizedCode = normalizeLanguageCode(languageCode);
        if (normalizedCode.isBlank()) {
            return Optional.empty();
        }
        for (MinecraftLanguageOption option : options) {
            if (normalizeLanguageCode(option.code()).equals(normalizedCode)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
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

    private static ConfigText resolvedUrlText(ChatglotConfig config) {
        String url = (config.localBackendBaseUrl == null || config.localBackendBaseUrl.isBlank())
            ? "http://127.0.0.1:" + config.localBackendPort
            : config.localBackendBaseUrl.trim();
        return ConfigText.translatable("chatglot.config.local_backend_resolved_url", url);
    }

    private static ConfigText resolvedSharedDirText(ChatglotRuntime runtime, ChatglotConfig config) {
        return ConfigText.translatable("chatglot.config.local_backend_resolved_shared_dir",
            LocalBackendPaths.resolveSharedRoot(config, runtime.configDir()).toString());
    }

    private static ConfigText resolvedModelPathText(ChatglotRuntime runtime, ChatglotConfig config) {
        return ConfigText.translatable("chatglot.config.local_backend_resolved_model_path",
            LocalBackendPaths.resolveModelPath(config, LocalBackendPaths.resolveSharedRoot(config, runtime.configDir())).toString());
    }

    private static ConfigText resolvedLogFileText(ChatglotRuntime runtime, ChatglotConfig config) {
        return ConfigText.translatable("chatglot.config.local_backend_log_file",
            LocalBackendPaths.logFile(LocalBackendPaths.resolveSharedRoot(config, runtime.configDir())).toString());
    }

    private static BooleanOption booleanOption(String id, String nameKey, boolean defaultValue) {
        return new BooleanOption(id, ConfigText.translatable(nameKey), ConfigText.empty(), defaultValue);
    }

    private static IntegerOption integerOption(String id, String nameKey, int defaultValue, int min, int max, int step) {
        return new IntegerOption(id, ConfigText.translatable(nameKey), ConfigText.empty(), defaultValue, min, max, step);
    }

    private static StringOption stringOption(String id, String nameKey, String defaultValue, int maxLength) {
        return new StringOption(id, ConfigText.translatable(nameKey), ConfigText.empty(), defaultValue, maxLength);
    }

    private static <T> SelectorOption<T> selectorOption(
        String id,
        String nameKey,
        T defaultValue,
        List<T> choices,
        ValueCodec<T> codec,
        java.util.function.Function<T, ConfigText> labelFactory
    ) {
        return             new SelectorOption<>(id, ConfigText.translatable(nameKey), ConfigText.empty(), defaultValue, choices, codec, labelFactory);
    }

    private static StringCodec stringCodec() {
        return new StringCodec();
    }

    private static final class StringCodec implements ValueCodec<String> {
        @Override public String encode(String value) { return value; }
        @Override public String decode(String value) { return value; }
    }

    private record MinecraftLanguageOption(String code, ConfigText label) {
        ConfigText labelText() {
            return label;
        }
    }

    private enum ProviderOption {
        DEFAULT("default"),
        GAS("gas"),
        DEEPL("deepl"),
        GOOGLE("google"),
        CODEX("codex"),
        TRANSLATEGEMMA_LOCAL("translategemma_local"),
        AZURE("azure"),
        OPENAI("openai"),
        CUSTOM_LLM("custom_llm"),
        GEMINI("gemini"),
        ANTHROPIC("anthropic");

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
                case "custom_llm", "openai_compatible" -> CUSTOM_LLM;
                case "gemini" -> GEMINI;
                case "anthropic" -> ANTHROPIC;
                case "translategemma_local" -> TRANSLATEGEMMA_LOCAL;
                default -> DEFAULT;
            };
        }
    }

    private enum OpenAiCompatibleProtocolOption {
        OPEN_RESPONSES(ChatglotConfig.OPENAI_COMPATIBLE_PROTOCOL_OPEN_RESPONSES),
        CHAT_COMPLETIONS(ChatglotConfig.OPENAI_COMPATIBLE_PROTOCOL_CHAT_COMPLETIONS);

        private final String id;

        OpenAiCompatibleProtocolOption(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        private static OpenAiCompatibleProtocolOption fromConfigValue(String value) {
            if (ChatglotConfig.OPENAI_COMPATIBLE_PROTOCOL_CHAT_COMPLETIONS.equals(value)) {
                return CHAT_COMPLETIONS;
            }
            return OPEN_RESPONSES;
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

    private static final class ProviderCodec implements ValueCodec<ProviderOption> {
        @Override
        public String encode(ProviderOption value) {
            return value.id();
        }

        @Override
        public ProviderOption decode(String value) {
            return ProviderOption.fromConfigValue(value);
        }
    }

    private static final class LanguageOptionCodec implements ValueCodec<MinecraftLanguageOption> {
        @Override
        public String encode(MinecraftLanguageOption value) {
            return value.code();
        }

        @Override
        public MinecraftLanguageOption decode(String value) {
            // Decoding is never used for language options because we always resolve
            // the persisted code back to a built option in the construction phase.
            // Returning the default keeps the codec contract satisfied for storage load.
            return new MinecraftLanguageOption(value, ConfigText.literal(value));
        }
    }

    private static final class EnumCodec<E extends Enum<E>> implements ValueCodec<E> {
        private final Class<E> type;

        private EnumCodec(Class<E> type) {
            this.type = type;
        }

        @Override
        public String encode(E value) {
            return value.name();
        }

        @Override
        public E decode(String value) {
            return Enum.valueOf(type, value);
        }
    }
}
