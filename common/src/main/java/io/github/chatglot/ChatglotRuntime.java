package io.github.chatglot;

import io.github.chatglot.config.ChatglotConfigManager;
import io.github.chatglot.translation.ChatTranslationService;
import io.github.chatglot.translation.LanguageDetectorService;
import io.github.chatglot.translation.TranslationProviderRegistry;
import io.github.chatglot.translation.TranslationRequestStore;
import io.github.chatglot.translation.provider.AnthropicTranslationProvider;
import io.github.chatglot.translation.provider.AzureTranslatorTranslationProvider;
import io.github.chatglot.translation.provider.CodexTranslationProvider;
import io.github.chatglot.translation.provider.DeepLTranslationProvider;
import io.github.chatglot.translation.provider.GasTranslationProvider;
import io.github.chatglot.translation.provider.GeminiTranslationProvider;
import io.github.chatglot.translation.provider.GoogleTranslationProvider;
import io.github.chatglot.translation.provider.OpenAiTranslationProvider;
import io.github.chatglot.translation.provider.TranslateGemmaLocalTranslationProvider;
import io.github.chatglot.translation.provider.codex.CodexModelCatalogService;
import io.github.chatglot.translation.provider.model.AnthropicModelCatalogService;
import io.github.chatglot.translation.provider.model.GeminiModelCatalogService;
import io.github.chatglot.translation.provider.model.OpenAiModelCatalogService;
import io.github.chatglot.translation.localbackend.LocalBackendManager;
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
    private final OpenAiModelCatalogService openAiModelCatalogService;
    private final GeminiModelCatalogService geminiModelCatalogService;
    private final AnthropicModelCatalogService anthropicModelCatalogService;
    private final LocalBackendManager localBackendManager;

    private ChatglotRuntime(Path configDir, Path gameDir) {
        this.configDir = configDir;
        this.gameDir = gameDir;
        this.configManager = new ChatglotConfigManager(configDir);
        this.codexModelCatalogService = new CodexModelCatalogService(configDir);
        this.codexModelCatalogService.initializeIfNeeded();
        this.openAiModelCatalogService = new OpenAiModelCatalogService(configDir);
        this.geminiModelCatalogService = new GeminiModelCatalogService(configDir);
        this.anthropicModelCatalogService = new AnthropicModelCatalogService(configDir);
        this.localBackendManager = new LocalBackendManager();
        this.providerRegistry = new TranslationProviderRegistry();
        this.providerRegistry.register(new DeepLTranslationProvider());
        this.providerRegistry.register(new GoogleTranslationProvider());
        this.providerRegistry.register(new GasTranslationProvider());
        this.providerRegistry.register(new CodexTranslationProvider());
        this.providerRegistry.register(new OpenAiTranslationProvider());
        this.providerRegistry.register(new GeminiTranslationProvider());
        this.providerRegistry.register(new AnthropicTranslationProvider());
        this.providerRegistry.register(new AzureTranslatorTranslationProvider());
        this.providerRegistry.register(new TranslateGemmaLocalTranslationProvider());
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

    public OpenAiModelCatalogService openAiModelCatalogService() {
        return openAiModelCatalogService;
    }

    public GeminiModelCatalogService geminiModelCatalogService() {
        return geminiModelCatalogService;
    }

    public AnthropicModelCatalogService anthropicModelCatalogService() {
        return anthropicModelCatalogService;
    }

    public LocalBackendManager localBackendManager() {
        return localBackendManager;
    }
}
