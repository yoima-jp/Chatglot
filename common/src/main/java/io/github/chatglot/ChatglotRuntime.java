package io.github.chatglot;

import io.github.chatglot.config.ChatglotConfigManager;
import io.github.chatglot.translation.ChatTranslationService;
import io.github.chatglot.translation.LanguageDetectorService;
import io.github.chatglot.translation.TranslationProviderRegistry;
import io.github.chatglot.translation.TranslationRequestStore;
import io.github.chatglot.translation.provider.CodexTranslationProvider;
import io.github.chatglot.translation.provider.DeepLTranslationProvider;
import io.github.chatglot.translation.provider.codex.CodexModelCatalogService;
import java.nio.file.Path;

public final class ChatglotRuntime {
    private static ChatglotRuntime instance;

    private final Path configDir;
    private final Path gameDir;
    private final ChatglotConfigManager configManager;
    private final TranslationProviderRegistry providerRegistry;
    private final ChatTranslationService translationService;
    private final LanguageDetectorService languageDetectorService;
    private final TranslationRequestStore requestStore;
    private final CodexModelCatalogService codexModelCatalogService;

    private ChatglotRuntime(Path configDir, Path gameDir) {
        this.configDir = configDir;
        this.gameDir = gameDir;
        this.configManager = new ChatglotConfigManager(configDir);
        this.codexModelCatalogService = new CodexModelCatalogService(configDir);
        this.codexModelCatalogService.initializeIfNeeded();
        this.providerRegistry = new TranslationProviderRegistry();
        this.providerRegistry.register(new DeepLTranslationProvider());
        this.providerRegistry.register(new CodexTranslationProvider());
        this.translationService = new ChatTranslationService(configManager, providerRegistry, configDir, gameDir);
        this.languageDetectorService = new LanguageDetectorService();
        this.requestStore = new TranslationRequestStore();
    }

    public static synchronized void initialize(Path configDir, Path gameDir) {
        if (instance == null) {
            instance = new ChatglotRuntime(configDir, gameDir);
        }
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public static ChatglotRuntime get() {
        if (instance == null) {
            throw new IllegalStateException("ChatglotRuntime is not initialized");
        }
        return instance;
    }

    public Path configDir() {
        return configDir;
    }

    public Path gameDir() {
        return gameDir;
    }

    public ChatglotConfigManager configManager() {
        return configManager;
    }

    public TranslationProviderRegistry providerRegistry() {
        return providerRegistry;
    }

    public ChatTranslationService translationService() {
        return translationService;
    }

    public LanguageDetectorService languageDetectorService() {
        return languageDetectorService;
    }

    public TranslationRequestStore requestStore() {
        return requestStore;
    }

    public CodexModelCatalogService codexModelCatalogService() {
        return codexModelCatalogService;
    }
}
