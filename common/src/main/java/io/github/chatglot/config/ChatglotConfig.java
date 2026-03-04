package io.github.chatglot.config;

import java.util.Locale;

public class ChatglotConfig {
    public boolean enabled = true;
    public boolean appendTranslateButton = true;
    public String translateButtonLabel = "T";

    public boolean autoTranslateEnabled = false;
    public String targetLanguage = "EN";
    public boolean showSourceLanguageTag = true;

    public String provider = "deepl";

    public String deeplApiKey = "";
    public boolean deeplUseFreeApi = true;

    public String codexPythonCommand = "python";
    public String codexScriptPath = "";
    public String codexTokenFile = "";
    public String codexModel = "gpt-5.3-codex";
    public String codexReasoningEffort = "medium";
    public String codexReasoningSummary = "auto";

    public int requestTimeoutSeconds = 45;

    public void sanitize() {
        if (translateButtonLabel == null || translateButtonLabel.isBlank()) {
            translateButtonLabel = "T";
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            targetLanguage = "EN";
        }
        targetLanguage = targetLanguage.trim().toUpperCase(Locale.ROOT);

        if (provider == null || provider.isBlank()) {
            provider = "deepl";
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);

        if (codexPythonCommand == null || codexPythonCommand.isBlank()) {
            codexPythonCommand = "python";
        }

        if (codexModel == null || codexModel.isBlank()) {
            codexModel = "gpt-5.3-codex";
        }

        if (codexReasoningEffort == null || codexReasoningEffort.isBlank()) {
            codexReasoningEffort = "medium";
        }

        if (requestTimeoutSeconds < 5) {
            requestTimeoutSeconds = 5;
        }
        if (requestTimeoutSeconds > 240) {
            requestTimeoutSeconds = 240;
        }

        if (deeplApiKey == null) {
            deeplApiKey = "";
        }
        if (codexScriptPath == null) {
            codexScriptPath = "";
        }
        if (codexTokenFile == null) {
            codexTokenFile = "";
        }
        if (codexReasoningSummary == null) {
            codexReasoningSummary = "";
        }
    }
}
