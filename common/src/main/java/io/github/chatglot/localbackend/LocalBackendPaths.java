package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.nio.file.Path;
import java.util.Locale;

public final class LocalBackendPaths {
    private LocalBackendPaths() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static Path resolveSharedRoot(ChatglotConfig config) {
        if (config.translategemmaLocalInstallDir != null && !config.translategemmaLocalInstallDir.isBlank()) {
            return Path.of(config.translategemmaLocalInstallDir.trim());
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            String userHome = System.getProperty("user.home", ".");
            return Path.of(userHome, "AppData", "Local", "ChatglotLocal");
        }

        return Path.of(localAppData, "ChatglotLocal");
    }

    public static Path runtimeDir(Path sharedRoot) { return sharedRoot.resolve("runtime"); }
    public static Path modelsDir(Path sharedRoot) { return sharedRoot.resolve("models"); }
    public static Path dataDir(Path sharedRoot) { return sharedRoot.resolve("data"); }
    public static Path logsDir(Path sharedRoot) { return sharedRoot.resolve("logs"); }
    public static Path stateFile(Path sharedRoot) { return sharedRoot.resolve("state.json"); }
}
