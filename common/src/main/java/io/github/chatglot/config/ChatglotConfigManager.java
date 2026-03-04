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
    private ChatglotConfig config;
    private boolean createdNewConfigFile;

    public ChatglotConfigManager(Path configDir) {
        Path modDir = configDir.resolve(ChatglotConstants.MOD_ID);
        this.path = modDir.resolve("chatglot.json");
        this.config = load();
    }

    public synchronized ChatglotConfig get() {
        return config;
    }

    public synchronized void reload() {
        this.config = load();
    }

    public synchronized boolean createdNewConfigFile() {
        return createdNewConfigFile;
    }

    public synchronized void save() {
        config.sanitize();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
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

            ChatglotConfig loaded = GSON.fromJson(Files.readString(path), ChatglotConfig.class);
            if (loaded == null) {
                loaded = new ChatglotConfig();
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
}
