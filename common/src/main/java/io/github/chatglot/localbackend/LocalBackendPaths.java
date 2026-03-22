package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotStoragePaths;
import java.nio.file.Path;

public final class LocalBackendPaths {
    private LocalBackendPaths() {
    }

    public static Path resolveSharedRoot(ChatglotConfig config, Path configDir) {
        return resolveSharedRoot(config, configDir, config.localBackendSharedDirectory);
    }

    public static Path resolveSharedRoot(ChatglotConfig config, Path configDir, String configuredRoot) {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Path.of(configuredRoot.trim());
        }

        return ChatglotStoragePaths.resolveDefaultLocalBackendRoot(config, configDir);
    }

    public static Path runtimeDir(Path root) {
        return root.resolve("runtime");
    }

    public static Path runtimeExecutable(Path root) {
        return runtimeDir(root).resolve("llama-server.exe");
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

}
