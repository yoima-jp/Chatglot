package io.github.chatglot.config;

import java.util.Locale;

public class ChatglotConfig {
    public static final String DEFAULT_TRANSLATE_BUTTON_LABEL = "✍";
    public static final String LEGACY_TRANSLATE_BUTTON_LABEL = "✍️";
    public static final String DEFAULT_PROVIDER = "default";
    public static final String GAS_DEFAULT_WEB_APP_URL =
        "https://script.google.com/macros/s/AKfycbyCriKw2zjqBZR1x_9u5pf16vzWuxGP7EO8UJ3AgoV8QpOto-hzmutBZS3eaNYZmlqw/exec";
    public static final String CODEX_DEFAULT_MODEL = "gpt-5.3-codex";
    public static final String OPENAI_DEFAULT_MODEL = "gpt-5-nano";
    public static final String GEMINI_DEFAULT_MODEL = "gemini-flash-latest";
    public static final String ANTHROPIC_DEFAULT_MODEL = "claude-haiku-4-5";
    public static final String AZURE_TRANSLATOR_DEFAULT_ENDPOINT = "https://api.cognitive.microsofttranslator.com";
    public static final int LOCAL_BACKEND_DEFAULT_PORT = 17870;
    public static final String LOCAL_BACKEND_DEFAULT_MODEL_ALIAS = "translategemma";
    public static final String LOCAL_BACKEND_DEFAULT_MODEL_FILE_NAME = "translategemma-4b-it.Q4_K_M.gguf";
    public static final String LOCAL_BACKEND_DEFAULT_MODEL_URL =
        "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.Q4_K_M.gguf?download=true";

    public static final String CODEX_REASONING_EFFORT_LOW = "low";
    public static final String CODEX_REASONING_EFFORT_MEDIUM = "medium";
    public static final String CODEX_REASONING_EFFORT_HIGH = "high";
    public static final String CODEX_REASONING_EFFORT_EXTRA_HIGH = "xhigh";

    public boolean enabled = true;
    public boolean appendTranslateButton = true;
    public String translateButtonLabel = DEFAULT_TRANSLATE_BUTTON_LABEL;

    public boolean autoTranslateEnabled = false;
    public boolean autoTranslateEnabledWhenSupported = false;
    public boolean overwriteOriginalWithTranslation = false;
    public boolean showTranslationPrefix = true;
    public boolean preserveLeadingSpeakerPrefix = true;
    public String targetLanguage = "EN";
    public boolean showSourceLanguageTag = true;

    public String provider = DEFAULT_PROVIDER;
    public boolean useSharedAppDataSettings = false;

    public String deeplApiKey = "";
    public boolean deeplUseFreeApi = true;
    public String googleTranslateApiKey = "";
    public String gasWebAppUrl = "";

    public String codexTokenFile = "";
    public String codexModel = CODEX_DEFAULT_MODEL;
    public String codexReasoningEffort = "medium";
    public String codexReasoningSummary = "auto";

    public String openaiApiKey = "";
    public String openaiModel = OPENAI_DEFAULT_MODEL;

    public String geminiApiKey = "";
    public String geminiModel = GEMINI_DEFAULT_MODEL;

    public String anthropicApiKey = "";
    public String anthropicModel = ANTHROPIC_DEFAULT_MODEL;

    public String azureTranslatorApiKey = "";
    public String azureTranslatorRegion = "";
    public String azureTranslatorEndpoint = AZURE_TRANSLATOR_DEFAULT_ENDPOINT;

    public String localBackendBaseUrl = "";
    public int localBackendPort = LOCAL_BACKEND_DEFAULT_PORT;
    public String localBackendSharedDirectory = "";
    public String localBackendCommand = "";
    public String localModelPath = "";
    public String localModelAlias = LOCAL_BACKEND_DEFAULT_MODEL_ALIAS;
    public String localModelFileName = LOCAL_BACKEND_DEFAULT_MODEL_FILE_NAME;
    public String localModelDownloadUrl = LOCAL_BACKEND_DEFAULT_MODEL_URL;

    public int requestTimeoutSeconds = 45;
    public int maxConcurrentTranslations = 1;

    public ChatglotConfig copy() {
        ChatglotConfig copy = new ChatglotConfig();
        copy.enabled = enabled;
        copy.appendTranslateButton = appendTranslateButton;
        copy.translateButtonLabel = translateButtonLabel;
        copy.autoTranslateEnabled = autoTranslateEnabled;
        copy.autoTranslateEnabledWhenSupported = autoTranslateEnabledWhenSupported;
        copy.overwriteOriginalWithTranslation = overwriteOriginalWithTranslation;
        copy.showTranslationPrefix = showTranslationPrefix;
        copy.preserveLeadingSpeakerPrefix = preserveLeadingSpeakerPrefix;
        copy.targetLanguage = targetLanguage;
        copy.showSourceLanguageTag = showSourceLanguageTag;
        copy.provider = provider;
        copy.useSharedAppDataSettings = useSharedAppDataSettings;
        copy.deeplApiKey = deeplApiKey;
        copy.deeplUseFreeApi = deeplUseFreeApi;
        copy.googleTranslateApiKey = googleTranslateApiKey;
        copy.gasWebAppUrl = gasWebAppUrl;
        copy.codexTokenFile = codexTokenFile;
        copy.codexModel = codexModel;
        copy.codexReasoningEffort = codexReasoningEffort;
        copy.codexReasoningSummary = codexReasoningSummary;
        copy.openaiApiKey = openaiApiKey;
        copy.openaiModel = openaiModel;
        copy.geminiApiKey = geminiApiKey;
        copy.geminiModel = geminiModel;
        copy.anthropicApiKey = anthropicApiKey;
        copy.anthropicModel = anthropicModel;
        copy.azureTranslatorApiKey = azureTranslatorApiKey;
        copy.azureTranslatorRegion = azureTranslatorRegion;
        copy.azureTranslatorEndpoint = azureTranslatorEndpoint;
        copy.localBackendBaseUrl = localBackendBaseUrl;
        copy.localBackendPort = localBackendPort;
        copy.localBackendSharedDirectory = localBackendSharedDirectory;
        copy.localBackendCommand = localBackendCommand;
        copy.localModelPath = localModelPath;
        copy.localModelAlias = localModelAlias;
        copy.localModelFileName = localModelFileName;
        copy.localModelDownloadUrl = localModelDownloadUrl;
        copy.requestTimeoutSeconds = requestTimeoutSeconds;
        copy.maxConcurrentTranslations = maxConcurrentTranslations;
        return copy;
    }

    public void applySharedSettingsFrom(ChatglotConfig other) {
        if (other == null) {
            return;
        }

        deeplApiKey = other.deeplApiKey;
        deeplUseFreeApi = other.deeplUseFreeApi;
        googleTranslateApiKey = other.googleTranslateApiKey;
        gasWebAppUrl = other.gasWebAppUrl;
        codexTokenFile = other.codexTokenFile;
        codexModel = other.codexModel;
        codexReasoningEffort = other.codexReasoningEffort;
        codexReasoningSummary = other.codexReasoningSummary;
        openaiApiKey = other.openaiApiKey;
        openaiModel = other.openaiModel;
        geminiApiKey = other.geminiApiKey;
        geminiModel = other.geminiModel;
        anthropicApiKey = other.anthropicApiKey;
        anthropicModel = other.anthropicModel;
        azureTranslatorApiKey = other.azureTranslatorApiKey;
        azureTranslatorRegion = other.azureTranslatorRegion;
        azureTranslatorEndpoint = other.azureTranslatorEndpoint;
        localBackendBaseUrl = other.localBackendBaseUrl;
        localBackendPort = other.localBackendPort;
        localBackendSharedDirectory = other.localBackendSharedDirectory;
        localBackendCommand = other.localBackendCommand;
        localModelPath = other.localModelPath;
        localModelAlias = other.localModelAlias;
        localModelFileName = other.localModelFileName;
        localModelDownloadUrl = other.localModelDownloadUrl;
    }

    public ChatglotConfig extractSharedSettings() {
        ChatglotConfig copy = new ChatglotConfig();
        copy.applySharedSettingsFrom(this);
        copy.sanitize();
        return copy;
    }

    public void clearSharedSettings() {
        deeplApiKey = "";
        deeplUseFreeApi = true;
        googleTranslateApiKey = "";
        gasWebAppUrl = "";
        codexTokenFile = "";
        codexModel = CODEX_DEFAULT_MODEL;
        codexReasoningEffort = CODEX_REASONING_EFFORT_MEDIUM;
        codexReasoningSummary = "auto";
        openaiApiKey = "";
        openaiModel = OPENAI_DEFAULT_MODEL;
        geminiApiKey = "";
        geminiModel = GEMINI_DEFAULT_MODEL;
        anthropicApiKey = "";
        anthropicModel = ANTHROPIC_DEFAULT_MODEL;
        azureTranslatorApiKey = "";
        azureTranslatorRegion = "";
        azureTranslatorEndpoint = AZURE_TRANSLATOR_DEFAULT_ENDPOINT;
        localBackendBaseUrl = "";
        localBackendPort = LOCAL_BACKEND_DEFAULT_PORT;
        localBackendSharedDirectory = "";
        localBackendCommand = "";
        localModelPath = "";
        localModelAlias = LOCAL_BACKEND_DEFAULT_MODEL_ALIAS;
        localModelFileName = LOCAL_BACKEND_DEFAULT_MODEL_FILE_NAME;
        localModelDownloadUrl = LOCAL_BACKEND_DEFAULT_MODEL_URL;
    }

    public static String normalizeCodexReasoningEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return CODEX_REASONING_EFFORT_MEDIUM;
        }

        return switch (effort.trim().toLowerCase(Locale.ROOT)) {
            case CODEX_REASONING_EFFORT_LOW, "none", "minimal" -> CODEX_REASONING_EFFORT_LOW;
            case CODEX_REASONING_EFFORT_MEDIUM -> CODEX_REASONING_EFFORT_MEDIUM;
            case CODEX_REASONING_EFFORT_HIGH -> CODEX_REASONING_EFFORT_HIGH;
            case CODEX_REASONING_EFFORT_EXTRA_HIGH, "extra high", "extra_high", "extra-high", "extrahigh" -> CODEX_REASONING_EFFORT_EXTRA_HIGH;
            default -> CODEX_REASONING_EFFORT_MEDIUM;
        };
    }

    public void sanitize() {
        if (
            translateButtonLabel == null
                || translateButtonLabel.isBlank()
                || LEGACY_TRANSLATE_BUTTON_LABEL.equals(translateButtonLabel)
        ) {
            translateButtonLabel = DEFAULT_TRANSLATE_BUTTON_LABEL;
        }
        if (autoTranslateEnabled) {
            autoTranslateEnabledWhenSupported = true;
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            targetLanguage = "EN";
        }
        targetLanguage = targetLanguage.trim().toUpperCase(Locale.ROOT);

        if (provider == null || provider.isBlank()) {
            provider = DEFAULT_PROVIDER;
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        switch (provider) {
            case "default", "deepl", "google", "gas", "codex", "openai", "gemini", "anthropic", "azure", "translategemma_local" -> {
            }
            default -> provider = DEFAULT_PROVIDER;
        }

        if (codexModel == null || codexModel.isBlank()) {
            codexModel = CODEX_DEFAULT_MODEL;
        }
        codexModel = codexModel.trim();

        if (codexReasoningEffort == null || codexReasoningEffort.isBlank()) {
            codexReasoningEffort = "medium";
        }
        codexReasoningEffort = normalizeCodexReasoningEffort(codexReasoningEffort);

        if (requestTimeoutSeconds < 5) {
            requestTimeoutSeconds = 5;
        }
        if (requestTimeoutSeconds > 240) {
            requestTimeoutSeconds = 240;
        }
        if (maxConcurrentTranslations < 1) {
            maxConcurrentTranslations = 1;
        }
        if (maxConcurrentTranslations > 16) {
            maxConcurrentTranslations = 16;
        }

        if (deeplApiKey == null) {
            deeplApiKey = "";
        }
        if (googleTranslateApiKey == null) {
            googleTranslateApiKey = "";
        }
        if (gasWebAppUrl == null) {
            gasWebAppUrl = "";
        }
        gasWebAppUrl = gasWebAppUrl.trim();
        if (codexTokenFile == null) {
            codexTokenFile = "";
        }
        if (openaiApiKey == null) {
            openaiApiKey = "";
        }
        if (openaiModel == null || openaiModel.isBlank()) {
            openaiModel = OPENAI_DEFAULT_MODEL;
        }
        openaiModel = openaiModel.trim();

        if (geminiApiKey == null) {
            geminiApiKey = "";
        }
        if (geminiModel == null || geminiModel.isBlank()) {
            geminiModel = GEMINI_DEFAULT_MODEL;
        }
        geminiModel = geminiModel.trim();

        if (anthropicApiKey == null) {
            anthropicApiKey = "";
        }
        if (anthropicModel == null || anthropicModel.isBlank()) {
            anthropicModel = ANTHROPIC_DEFAULT_MODEL;
        }
        anthropicModel = anthropicModel.trim();

        if (azureTranslatorApiKey == null) {
            azureTranslatorApiKey = "";
        }
        if (azureTranslatorRegion == null) {
            azureTranslatorRegion = "";
        }
        if (azureTranslatorEndpoint == null || azureTranslatorEndpoint.isBlank()) {
            azureTranslatorEndpoint = AZURE_TRANSLATOR_DEFAULT_ENDPOINT;
        }
        azureTranslatorEndpoint = azureTranslatorEndpoint.trim();
        azureTranslatorRegion = azureTranslatorRegion.trim();

        if (localBackendBaseUrl == null) {
            localBackendBaseUrl = "";
        }
        localBackendBaseUrl = localBackendBaseUrl.trim();
        if (localBackendPort < 1024 || localBackendPort > 65535) {
            localBackendPort = LOCAL_BACKEND_DEFAULT_PORT;
        }
        if (localBackendSharedDirectory == null) {
            localBackendSharedDirectory = "";
        }
        localBackendSharedDirectory = localBackendSharedDirectory.trim();
        if (localBackendCommand == null) {
            localBackendCommand = "";
        }
        localBackendCommand = localBackendCommand.trim();
        if (localModelPath == null) {
            localModelPath = "";
        }
        localModelPath = localModelPath.trim();
        if (localModelAlias == null || localModelAlias.isBlank()) {
            localModelAlias = LOCAL_BACKEND_DEFAULT_MODEL_ALIAS;
        }
        localModelAlias = localModelAlias.trim();
        if (localModelFileName == null || localModelFileName.isBlank()) {
            localModelFileName = LOCAL_BACKEND_DEFAULT_MODEL_FILE_NAME;
        }
        localModelFileName = localModelFileName.trim();
        if (
            "txgemma-2b-predict-Q4_K_M.gguf".equals(localModelFileName)
                || "txgemma-2b-predict-q4_k_m.gguf".equals(localModelFileName)
        ) {
            localModelFileName = LOCAL_BACKEND_DEFAULT_MODEL_FILE_NAME;
        }
        if (
            "https://huggingface.co/matrixportalx/txgemma-2b-predict-GGUF/resolve/main/txgemma-2b-predict-Q4_K_M.gguf?download=true".equals(localModelDownloadUrl)
                || "https://huggingface.co/matrixportalx/txgemma-2b-predict-GGUF/resolve/main/txgemma-2b-predict-q4_k_m.gguf?download=true".equals(localModelDownloadUrl)
        ) {
            localModelDownloadUrl = LOCAL_BACKEND_DEFAULT_MODEL_URL;
        }
        if (localModelDownloadUrl == null || localModelDownloadUrl.isBlank()) {
            localModelDownloadUrl = LOCAL_BACKEND_DEFAULT_MODEL_URL;
        }
        localModelDownloadUrl = localModelDownloadUrl.trim();

        codexTokenFile = codexTokenFile.trim();
        if (codexReasoningSummary == null) {
            codexReasoningSummary = "auto";
        }
        codexReasoningSummary = codexReasoningSummary.trim();

        autoTranslateEnabled = autoTranslateEnabledWhenSupported && !DEFAULT_PROVIDER.equals(provider);
    }
}

