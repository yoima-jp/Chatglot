package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class LocalBackendInstaller {
    private static final String LLAMA_CPP_WINGET_ID = "ggml.llamacpp";

    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

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
            "Chatglot installs TranslateGemma support via llama.cpp.\n"
                + "Setup from the config screen installs llama.cpp with winget and downloads the default GGUF model.\n"
                + "If you want to override the runtime, set 'Backend command path override' in Chatglot settings.\n"
        );
    }

    public Path ensureRuntime(ChatglotConfig config) throws IOException {
        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            Path override = Path.of(config.localBackendCommand.trim());
            if (!Files.exists(override)) {
                throw new IOException("Configured backend command was not found: " + override);
            }
            return override;
        }

        Path discovered = findLlamaServerExecutable();
        if (discovered != null) {
            return discovered;
        }

        List<String> command = List.of(
            "winget",
            "install",
            "-e",
            "--id",
            LLAMA_CPP_WINGET_ID,
            "--accept-package-agreements",
            "--accept-source-agreements",
            "--disable-interactivity"
        );
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("Command failed (" + exitCode + "): " + String.join(" ", command) + "\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("winget install was interrupted.", e);
        }

        discovered = findLlamaServerExecutable();
        if (discovered != null) {
            return discovered;
        }
        throw new IOException("Installed llama.cpp with winget, but llama-server.exe was not found. Check " + LocalBackendPaths.wingetPackagesDir());
    }

    public Path ensureModelDownloaded(ChatglotConfig config, Path sharedRoot) throws IOException {
        Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
        if (Files.exists(modelPath) && Files.size(modelPath) > 0) {
            return modelPath;
        }
        if (config.localModelDownloadUrl == null || config.localModelDownloadUrl.isBlank()) {
            throw new IOException("Model download URL is empty.");
        }

        Files.createDirectories(modelPath.getParent());
        Path tempPath = modelPath.resolveSibling(modelPath.getFileName() + ".part");
        Files.deleteIfExists(tempPath);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.localModelDownloadUrl.trim()))
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "Chatglot")
            .GET()
            .build();

        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempPath));
            if (response.statusCode() >= 400) {
                Files.deleteIfExists(tempPath);
                throw new IOException("Model download failed with HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(tempPath);
            throw new IOException("Model download was interrupted.", e);
        }

        try {
            Files.move(tempPath, modelPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(tempPath, modelPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return modelPath;
    }

    private static Path findLlamaServerExecutable() throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(LocalBackendPaths.wingetLinksDir().resolve("llama-server.exe"));

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String rawSegment : pathEnv.split(";")) {
                if (rawSegment == null || rawSegment.isBlank()) {
                    continue;
                }
                candidates.add(Path.of(rawSegment.trim()).resolve("llama-server.exe"));
            }
        }

        Path packagesDir = LocalBackendPaths.wingetPackagesDir();
        if (Files.isDirectory(packagesDir)) {
            try (DirectoryStream<Path> packages = Files.newDirectoryStream(packagesDir, "ggml.llamacpp*")) {
                for (Path packageDir : packages) {
                    candidates.add(packageDir.resolve("llama-server.exe"));
                    candidates.add(packageDir.resolve("llama-bundle").resolve("llama-server.exe"));
                }
            }
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
