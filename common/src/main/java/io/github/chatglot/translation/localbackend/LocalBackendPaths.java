package io.github.chatglot.translation.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.nio.file.Path;

public record LocalBackendPaths(Path baseDir, Path runtimeDir, Path modelsDir, Path dataDir, Path logsDir, Path stateFile) {
    public static LocalBackendPaths fromConfig(ChatglotConfig config) {
        Path base = resolveBaseDir(config.translategemmaLocalInstallDir);
        return new LocalBackendPaths(
            base,
            base.resolve("runtime"),
            base.resolve("models"),
            base.resolve("data"),
            base.resolve("logs"),
            base.resolve("state.json")
        );
    }

    public static Path resolveBaseDir(String configuredDir) {
        if (configuredDir != null && !configuredDir.isBlank()) {
            return Path.of(configuredDir.trim());
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("ChatglotLocal");
        }

        return Path.of(System.getProperty("user.home", "."), "AppData", "Local", "ChatglotLocal");
    }
}
