package io.github.chatglot.config;

import java.util.Set;
import java.util.Locale;

public class ChatglotConfig {
    private static final Set<String> CODEX_REASONING_EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh");

    public boolean enabled = true;
    public boolean appendTranslateButton = true;
    public String translateButtonLabel = "✍️";

    public boolean autoTranslateEnabled = false;
    public String targetLanguage = "EN";
    public boolean showSourceLanguageTag = true;

    public String provider = "deepl";

    public String deeplApiKey = "";
    public boolean deeplUseFreeApi = true;

    public String codexTokenFile = "";
    public String codexModel = "gpt-5.3-codex";
    public String codexReasoningEffort = "medium";
    public String codexReasoningSummary = "auto";

    public int requestTimeoutSeconds = 45;

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
            codexModel = "gpt-5.3-codex";
        }
        codexModel = codexModel.trim();

        if (codexReasoningEffort == null || codexReasoningEffort.isBlank()) {
            codexReasoningEffort = "medium";
        }
        codexReasoningEffort = codexReasoningEffort.trim().toLowerCase(Locale.ROOT);
        if (!CODEX_REASONING_EFFORTS.contains(codexReasoningEffort)) {
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
        if (codexTokenFile == null) {
            codexTokenFile = "";
        }
        codexTokenFile = codexTokenFile.trim();
        if (codexReasoningSummary == null) {
            codexReasoningSummary = "auto";
        }
        codexReasoningSummary = codexReasoningSummary.trim();
    }
}
