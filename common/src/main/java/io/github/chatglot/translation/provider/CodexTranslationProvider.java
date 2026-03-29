package io.github.chatglot.translation.provider;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotStoragePaths;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import io.github.chatglot.translation.provider.codex.CodexAuthTokens;
import io.github.chatglot.translation.provider.codex.CodexOAuthService;
import io.github.chatglot.translation.provider.codex.CodexResponsesService;
import java.nio.file.Path;

public final class CodexTranslationProvider implements TranslationProvider {
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
            TranslationPromptBuilder.buildStandardPrompt(request),
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
        return ChatglotStoragePaths.resolveDefaultCodexTokenFile(config, configDir);
    }
}
