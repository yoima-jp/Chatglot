package io.github.chatglot.localbackend;

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

    public static Path stateFile(Path root) {
        return root.resolve("state.json");
    }
}
