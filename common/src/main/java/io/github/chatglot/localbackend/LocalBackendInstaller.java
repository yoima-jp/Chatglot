package io.github.chatglot.localbackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendInstaller {
    public void ensureLayout(Path sharedRoot) throws IOException {
        Files.createDirectories(LocalBackendPaths.runtimeDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.modelsDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.dataDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.logsDir(sharedRoot));
    }

    public void ensureRuntimePlaceholder(Path sharedRoot) throws IOException {
        Path runtimeDir = LocalBackendPaths.runtimeDir(sharedRoot);
        Path readme = runtimeDir.resolve("README.txt");
        if (Files.exists(readme)) {
            return;
        }

        Files.writeString(
            readme,
            "Place your local backend executable/script in this runtime directory.\n"
                + "Default command expected by Chatglot: chatglot-local-backend.cmd\n"
                + "The backend must expose localhost HTTP APIs (/health or /v1/models and /v1/chat/completions).\n"
        );
    }
}
