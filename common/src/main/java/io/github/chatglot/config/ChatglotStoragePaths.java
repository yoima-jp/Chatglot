package io.github.chatglot.config;

import io.github.chatglot.ChatglotConstants;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChatglotStoragePaths {
    private static final String APPDATA_ROOT_NAME = "Chatglot";
    private static final String LEGACY_LOCAL_BACKEND_ROOT_NAME = "ChatglotLocal";
    private static final String LOCAL_BACKEND_DIR_NAME = "local-backend";
    private static final String SHARED_SETTINGS_FILE_NAME = "shared-settings.json";
    private static final String CODEX_TOKEN_FILENAME = "codex_tokens.json";

    private ChatglotStoragePaths() {
    }

    public static Path resolveModConfigRoot(Path configDir) {
        return configDir.resolve(ChatglotConstants.MOD_ID);
    }

    public static Path resolveSharedSettingsFile() {
        return resolveLocalAppDataRoot().resolve(SHARED_SETTINGS_FILE_NAME);
    }

    public static Path resolveDefaultCodexTokenFile(ChatglotConfig config, Path configDir) {
        Path legacyConfigTokenFile = resolveModConfigRoot(configDir).resolve(CODEX_TOKEN_FILENAME);
        if (config.useSharedAppDataSettings) {
            Path sharedTokenFile = resolveLocalAppDataRoot().resolve(CODEX_TOKEN_FILENAME);
            if (Files.exists(sharedTokenFile) || !Files.exists(legacyConfigTokenFile)) {
                return sharedTokenFile;
            }
            return legacyConfigTokenFile;
        }
        return legacyConfigTokenFile;
    }

    public static Path resolveDefaultLocalBackendRoot(ChatglotConfig config, Path configDir) {
        if (config.useSharedAppDataSettings) {
            return resolveLegacyLocalBackendRoot();
        }
        return resolveModConfigRoot(configDir).resolve(LOCAL_BACKEND_DIR_NAME);
    }

    public static Path resolveLocalAppDataRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve(APPDATA_ROOT_NAME);
        }

        return Path.of(System.getProperty("user.home", ".")).resolve("AppData").resolve("Local").resolve(APPDATA_ROOT_NAME);
    }

    public static Path resolveLegacyLocalBackendRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve(LEGACY_LOCAL_BACKEND_ROOT_NAME);
        }

        return Path.of(System.getProperty("user.home", "."))
            .resolve("AppData")
            .resolve("Local")
            .resolve(LEGACY_LOCAL_BACKEND_ROOT_NAME);
    }

    public static boolean hasLegacyLocalBackendInstall() {
        Path legacyRoot = resolveLegacyLocalBackendRoot();
        return Files.exists(legacyRoot.resolve("state.json"))
            || Files.exists(legacyRoot.resolve("models"))
            || Files.exists(legacyRoot.resolve("runtime"));
    }
}
