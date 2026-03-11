package io.github.chatglot.localbackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendInstaller {
    public void ensureInstalled(Path sharedRoot) throws IOException {
        Files.createDirectories(sharedRoot);
        Files.createDirectories(LocalBackendPaths.runtimeDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.modelsDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.dataDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.logsDir(sharedRoot));

        Path readme = LocalBackendPaths.runtimeDir(sharedRoot).resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(
                readme,
                "Chatglot local backend runtime folder.\n"
                    + "Place your local backend executable at runtime/chatglot-local-backend.exe\n"
                    + "The executable should expose localhost OpenAI-compatible APIs and support: --port, --model.\n"
            );
        }
    }
}
