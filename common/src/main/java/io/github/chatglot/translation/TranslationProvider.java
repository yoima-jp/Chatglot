package io.github.chatglot.translation;

import io.github.chatglot.config.ChatglotConfig;
import java.nio.file.Path;

public interface TranslationProvider {
    String id();

    TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException;
}
