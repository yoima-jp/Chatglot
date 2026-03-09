package io.github.chatglot.config;

import java.util.Locale;

public class ChatglotConfig {
    public static final String CODEX_DEFAULT_MODEL = "gpt-5.3-codex";
    public static final String OPENAI_DEFAULT_MODEL = "gpt-5-nano";
    public static final String GEMINI_DEFAULT_MODEL = "gemini-flash-latest";
    public static final String ANTHROPIC_DEFAULT_MODEL = "claude-haiku-4-5";

    public static final String CODEX_REASONING_EFFORT_LOW = "low";
    public static final String CODEX_REASONING_EFFORT_MEDIUM = "medium";
    public static final String CODEX_REASONING_EFFORT_HIGH = "high";
    public static final String CODEX_REASONING_EFFORT_EXTRA_HIGH = "xhigh";

    public boolean enabled = true;
    public boolean appendTranslateButton = true;
    public String translateButtonLabel = "✍️";

    public boolean autoTranslateEnabled = false;
    public boolean overwriteOriginalWithTranslation = false;
    public String targetLanguage = "EN";
    public boolean showSourceLanguageTag = true;

    public String provider = "deepl";

    public String deeplApiKey = "";
    public boolean deeplUseFreeApi = true;

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

    public int requestTimeoutSeconds = 45;

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
        if (translateButtonLabel == null || translateButtonLabel.isBlank()) {
            translateButtonLabel = "✍️";
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            targetLanguage = "EN";
        }
        targetLanguage = targetLanguage.trim().toUpperCase(Locale.ROOT);

        if (provider == null || provider.isBlank()) {
            provider = "deepl";
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);

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

        if (deeplApiKey == null) {
            deeplApiKey = "";
        }
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

        codexTokenFile = codexTokenFile.trim();
        if (codexReasoningSummary == null) {
            codexReasoningSummary = "auto";
        }
        codexReasoningSummary = codexReasoningSummary.trim();
    }
}
