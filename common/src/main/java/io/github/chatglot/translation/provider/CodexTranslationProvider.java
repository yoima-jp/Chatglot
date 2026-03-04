package io.github.chatglot.translation.provider;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import io.github.chatglot.translation.provider.codex.CodexAuthTokens;
import io.github.chatglot.translation.provider.codex.CodexOAuthService;
import io.github.chatglot.translation.provider.codex.CodexResponsesService;
import java.nio.file.Path;

public final class CodexTranslationProvider implements TranslationProvider {
    private static final String TOKEN_FILENAME = "codex_tokens.json";

    private final CodexOAuthService oauthService = new CodexOAuthService();
    private final CodexResponsesService responsesService = new CodexResponsesService();

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        Path tokenFile = resolveTokenFile(config, configDir);
        CodexAuthTokens tokens = oauthService.ensureTokens(tokenFile);
        String translated = responsesService.translate(
            tokens,
            config.codexModel,
            buildPrompt(request),
            config.codexReasoningEffort,
            config.codexReasoningSummary,
            config.requestTimeoutSeconds
        );
        if (translated == null || translated.isBlank()) {
            throw new TranslationException("Codex returned empty output.");
        }
        return new TranslationResult(translated, request.sourceLanguageHint(), id());
    }

    private static Path resolveTokenFile(ChatglotConfig config, Path configDir) {
        if (config.codexTokenFile != null && !config.codexTokenFile.isBlank()) {
            return Path.of(config.codexTokenFile.trim());
        }
        return configDir.resolve(ChatglotConstants.MOD_ID).resolve(TOKEN_FILENAME);
    }

    private static String buildPrompt(TranslationRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("Translate the following Minecraft chat message into ")
            .append(request.targetLanguage())
            .append(". ")
            .append("Preserve player names, commands, URLs, placeholders, and formatting markers when possible. ")
            .append("Return only translated text without explanations.");

        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            builder.append(" Source language hint: ").append(request.sourceLanguageHint()).append('.');
        }

        builder.append("\n\nMessage:\n").append(request.text());
        return builder.toString();
    }
}
