package io.github.chatglot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.chatglot.ChatglotConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatglotConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatglotConstants.MOD_NAME);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path path;
    private final Path sharedPath;
    private ChatglotConfig config;
    private ChatglotConfig persistedConfig;
    private boolean createdNewConfigFile;

    public ChatglotConfigManager(Path configDir) {
        Path modDir = ChatglotStoragePaths.resolveModConfigRoot(configDir);
        this.path = modDir.resolve("chatglot.json");
        this.sharedPath = ChatglotStoragePaths.resolveSharedSettingsFile();
        this.config = load();
        this.persistedConfig = this.config.copy();
    }

    public synchronized ChatglotConfig get() {
        return config;
    }

    public synchronized void reload() {
        this.config = load();
        this.persistedConfig = this.config.copy();
    }

    public synchronized boolean createdNewConfigFile() {
        return createdNewConfigFile;
    }

    public synchronized void save() {
        config.sanitize();
        try {
            ChatglotConfig effective = config.copy();
            effective.sanitize();
            boolean wasUsingSharedSettings = persistedConfig != null && persistedConfig.useSharedAppDataSettings;
            boolean sharedModeChanged = persistedConfig == null || wasUsingSharedSettings != effective.useSharedAppDataSettings;

            Files.createDirectories(path.getParent());
            if (effective.useSharedAppDataSettings) {
                boolean switchedToSharedSettings = sharedModeChanged && !wasUsingSharedSettings;
                boolean preserveExistingSharedSettings = switchedToSharedSettings && Files.exists(sharedPath);
                if (preserveExistingSharedSettings) {
                    effective.applySharedSettingsFrom(loadSharedSettings(effective));
                    effective.sanitize();
                }

                ChatglotConfig primary = effective.copy();
                primary.clearSharedSettings();
                primary.sanitize();
                Files.writeString(path, GSON.toJson(primary));

                if (!preserveExistingSharedSettings) {
                    ChatglotConfig shared = effective.extractSharedSettings();
                    Files.createDirectories(sharedPath.getParent());
                    Files.writeString(sharedPath, GSON.toJson(shared));
                }
            } else {
                Files.writeString(path, GSON.toJson(effective));
            }
            config = effective;
            persistedConfig = effective.copy();
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", path, e);
        }
    }

    private ChatglotConfig load() {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                ChatglotConfig defaults = new ChatglotConfig();
                defaults.sanitize();
                Files.writeString(path, GSON.toJson(defaults));
                createdNewConfigFile = true;
                return defaults;
            }

            String raw = Files.readString(path);
            ChatglotConfig loaded = GSON.fromJson(raw, ChatglotConfig.class);
            if (loaded == null) {
                loaded = new ChatglotConfig();
            }
            boolean migratedLegacySharedPreference = migrateLegacySharedStoragePreference(raw, loaded);
            if (loaded.useSharedAppDataSettings) {
                loaded.applySharedSettingsFrom(loadSharedSettings(migratedLegacySharedPreference ? loaded : null));
            }
            loaded.sanitize();
            createdNewConfigFile = false;
            return loaded;
        } catch (Exception e) {
            LOGGER.error("Failed to load config, using defaults: {}", path, e);
            ChatglotConfig defaults = new ChatglotConfig();
            defaults.sanitize();
            createdNewConfigFile = false;
            return defaults;
        }
    }

    private ChatglotConfig loadSharedSettings(ChatglotConfig fallback) {
        try {
            Files.createDirectories(sharedPath.getParent());
            if (!Files.exists(sharedPath)) {
                return fallback != null ? fallback.extractSharedSettings() : new ChatglotConfig();
            }

            ChatglotConfig shared = GSON.fromJson(Files.readString(sharedPath), ChatglotConfig.class);
            if (shared == null) {
                shared = fallback != null ? fallback.extractSharedSettings() : new ChatglotConfig();
            }
            shared.sanitize();
            return shared;
        } catch (Exception e) {
            LOGGER.warn("Failed to load shared Chatglot settings: {}", sharedPath, e);
            return fallback != null ? fallback.extractSharedSettings() : new ChatglotConfig();
        }
    }

    private boolean migrateLegacySharedStoragePreference(String raw, ChatglotConfig loaded) {
        if (raw != null && raw.contains("\"useSharedAppDataSettings\"")) {
            return false;
        }

        if (ChatglotStoragePaths.hasLegacyLocalBackendInstall()) {
            loaded.useSharedAppDataSettings = true;
            return true;
        }
        return false;
    }
}
