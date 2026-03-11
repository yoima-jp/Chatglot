package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendInstaller {
    public Path prepareDirectories(ChatglotConfig config) throws IOException {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config);
        Files.createDirectories(LocalBackendPaths.runtimeDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.modelsDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.dataDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.logsDir(sharedRoot));

        Path runtimeReadme = LocalBackendPaths.runtimeDir(sharedRoot).resolve("README.txt");
        if (!Files.exists(runtimeReadme)) {
            Files.writeString(
                runtimeReadme,
                "Place your local backend runtime here. Configure 'Backend launch command' in Chatglot settings.\n"
            );
        }

        return sharedRoot;
    }
}
