package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.nio.file.Path;

public final class LocalBackendPaths {
    private static final String DEFAULT_ROOT_NAME = "ChatglotLocal";

    private LocalBackendPaths() {
    }

    public static Path resolveSharedRoot(String configuredRoot) {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Path.of(configuredRoot.trim());
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve(DEFAULT_ROOT_NAME);
        }

        return Path.of(System.getProperty("user.home", ".")).resolve("AppData").resolve("Local").resolve(DEFAULT_ROOT_NAME);
    }

    public static Path runtimeDir(Path root) {
        return root.resolve("runtime");
    }

    public static Path modelsDir(Path root) {
        return root.resolve("models");
    }

    public static Path dataDir(Path root) {
        return root.resolve("data");
    }

    public static Path logsDir(Path root) {
        return root.resolve("logs");
    }

    public static Path logFile(Path root) {
        return logsDir(root).resolve("backend.log");
    }

    public static Path stateFile(Path root) {
        return root.resolve("state.json");
    }

    public static Path resolveModelPath(ChatglotConfig config, Path root) {
        if (config.localModelPath != null && !config.localModelPath.isBlank()) {
            return Path.of(config.localModelPath.trim());
        }
        return modelsDir(root).resolve(config.localModelFileName == null || config.localModelFileName.isBlank() ? ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_FILE_NAME : config.localModelFileName.trim());
    }

    public static Path wingetLinksDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("Microsoft").resolve("WinGet").resolve("Links");
        }
        return Path.of(System.getProperty("user.home", ".")).resolve("AppData").resolve("Local").resolve("Microsoft").resolve("WinGet").resolve("Links");
    }

    public static Path wingetPackagesDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("Microsoft").resolve("WinGet").resolve("Packages");
        }
        return Path.of(System.getProperty("user.home", ".")).resolve("AppData").resolve("Local").resolve("Microsoft").resolve("WinGet").resolve("Packages");
    }
}
