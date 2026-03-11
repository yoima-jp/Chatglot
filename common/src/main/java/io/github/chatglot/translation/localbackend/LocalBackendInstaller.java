package io.github.chatglot.translation.localbackend;

import java.io.IOException;
import java.nio.file.Files;

public final class LocalBackendInstaller {
    public void ensureInstalled(LocalBackendPaths paths) throws IOException {
        Files.createDirectories(paths.baseDir());
        Files.createDirectories(paths.runtimeDir());
        Files.createDirectories(paths.modelsDir());
        Files.createDirectories(paths.dataDir());
        Files.createDirectories(paths.logsDir());

        var readme = paths.runtimeDir().resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(
                readme,
                "Chatglot local backend runtime directory.\n"
                    + "Install your backend runtime here or point the launcher command to an existing executable.\n"
                    + "Model files are not auto-downloaded in this version; set a local model path manually.\n"
            );
        }
    }
}
