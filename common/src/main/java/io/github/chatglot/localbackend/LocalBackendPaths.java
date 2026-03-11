package io.github.chatglot.localbackend;

import java.nio.file.Path;
import java.util.Locale;

public final class LocalBackendPaths {
    private LocalBackendPaths() {
    }

    public static Path resolveSharedRoot(String configuredInstallDir) {
        if (configuredInstallDir != null && !configuredInstallDir.isBlank()) {
            return Path.of(configuredInstallDir.trim());
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData.trim(), "ChatglotLocal");
        }

        return Path.of(System.getProperty("user.home"), "AppData", "Local", "ChatglotLocal");
    }

    public static Path runtimeDir(Path sharedRoot) {
        return sharedRoot.resolve("runtime");
    }

    public static Path modelsDir(Path sharedRoot) {
        return sharedRoot.resolve("models");
    }

    public static Path dataDir(Path sharedRoot) {
        return sharedRoot.resolve("data");
    }

    public static Path logsDir(Path sharedRoot) {
        return sharedRoot.resolve("logs");
    }

    public static Path stateFile(Path sharedRoot) {
        return sharedRoot.resolve("state.json");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
