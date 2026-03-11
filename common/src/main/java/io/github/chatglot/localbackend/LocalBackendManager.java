package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class LocalBackendManager {
    private final LocalBackendInstaller installer = new LocalBackendInstaller();
    private final LocalBackendHealthChecker healthChecker = new LocalBackendHealthChecker();

    public CompletableFuture<LocalBackendStatus> setupAndStartAsync(ChatglotConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return setupAndStart(config);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    public CompletableFuture<LocalBackendStatus> checkStatusAsync(ChatglotConfig config) {
        return CompletableFuture.supplyAsync(() -> checkStatus(config));
    }

    public LocalBackendStatus checkStatus(ChatglotConfig config) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus(false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), "Unsupported OS. Local TranslateGemma is currently Windows-only.");
        }

        Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
        boolean healthy = healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds));
        boolean modelPresent = Files.exists(modelPath);
        String runtimeMessage = resolveRuntimeMessage(config);
        String message = healthy
            ? "Backend is healthy. " + runtimeMessage + " Model: " + modelPath
            : (!modelPresent ? "Backend is not reachable. " + runtimeMessage + " Model is missing: " + modelPath : "Backend is not reachable. " + runtimeMessage + " Model: " + modelPath);
        return new LocalBackendStatus(true, healthy, healthy, baseUrl, sharedRoot, modelPath.toString(), message);
    }

    public LocalBackendStatus setupAndStart(ChatglotConfig config) throws IOException {
        return setupAndStart(config, message -> {
        });
    }

    public LocalBackendStatus setupAndStart(ChatglotConfig config, Consumer<String> progressListener) throws IOException {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus(false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), "Unsupported OS. Local TranslateGemma is currently Windows-only.");
        }

        progressListener.accept("Preparing local backend folders...");
        installer.ensureLayout(sharedRoot);
        installer.ensureRuntimePlaceholder(sharedRoot);

        LocalBackendStateStore store = new LocalBackendStateStore(sharedRoot);
        LocalBackendState state = store.load();
        state.port = config.localBackendPort;

        progressListener.accept("Checking llama.cpp runtime...");
        Path runtimePath = installer.ensureRuntime(config, progressListener);
        progressListener.accept("Checking TranslateGemma model...");
        Path modelPath = installer.ensureModelDownloaded(config, sharedRoot, progressListener);
        state.runtimePath = sharedRoot.toString();
        state.executablePath = runtimePath.toString();
        state.modelPath = modelPath.toString();
        state.downloadUrl = config.localModelDownloadUrl == null ? "" : config.localModelDownloadUrl.trim();

        if (healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds))) {
            state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
            store.save(state);
            return new LocalBackendStatus(true, true, true, baseUrl, sharedRoot, state.modelPath, "Backend already running and healthy. Runtime: " + runtimePath + " Model: " + modelPath);
        }

        progressListener.accept("Starting llama-server...");
        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(config, runtimePath, modelPath));
        processBuilder.directory(sharedRoot.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(LocalBackendPaths.logFile(sharedRoot).toFile()));
        Process process = processBuilder.start();
        state.pid = process.pid();
        store.save(state);

        long startedAt = System.currentTimeMillis();
        progressListener.accept("Waiting for backend health check...");
        while (System.currentTimeMillis() - startedAt < 15000) {
            if (healthChecker.isHealthy(baseUrl, 2)) {
                state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
                store.save(state);
                return new LocalBackendStatus(true, true, true, baseUrl, sharedRoot, modelPath.toString(), "Backend started and healthy. Runtime: " + runtimePath + " Model: " + modelPath);
            }
            if (!process.isAlive()) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new LocalBackendStatus(true, false, process.isAlive(), baseUrl, sharedRoot, modelPath.toString(), "Backend started but health check failed. Check " + LocalBackendPaths.logFile(sharedRoot));
    }

    public LocalBackendStatus ensureBackendAvailable(ChatglotConfig config) {
        LocalBackendStatus status = checkStatus(config);
        if (status.healthy()) {
            return status;
        }
        try {
            return setupAndStart(config);
        } catch (Exception e) {
            return new LocalBackendStatus(status.supported(), false, false, status.backendUrl(), status.sharedRoot(), status.modelPath(), "Failed to start backend: " + e.getMessage());
        }
    }

    public LocalBackendStatus downloadModel(ChatglotConfig config) {
        return downloadModel(config, message -> {
        });
    }

    public LocalBackendStatus downloadModel(ChatglotConfig config, Consumer<String> progressListener) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus(false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), "Unsupported OS. Local TranslateGemma is currently Windows-only.");
        }
        try {
            progressListener.accept("Preparing local backend folders...");
            installer.ensureLayout(sharedRoot);
            Path modelPath = installer.ensureModelDownloaded(config, sharedRoot, progressListener);
            return new LocalBackendStatus(true, false, false, baseUrl, sharedRoot, modelPath.toString(), "Model is ready: " + modelPath);
        } catch (Exception e) {
            return new LocalBackendStatus(true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), "Model download failed: " + e.getMessage());
        }
    }

    private static String resolveBaseUrl(ChatglotConfig config) {
        if (config.localBackendBaseUrl != null && !config.localBackendBaseUrl.isBlank()) {
            return config.localBackendBaseUrl.trim().replaceAll("/+$", "");
        }
        return "http://127.0.0.1:" + config.localBackendPort;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String resolveRuntimeMessage(ChatglotConfig config) {
        try {
            return "Runtime: " + installer.ensureRuntime(config);
        } catch (Exception e) {
            return "Runtime is not ready (" + e.getMessage() + ").";
        }
    }

    private static List<String> buildCommand(ChatglotConfig config, Path runtimePath, Path modelPath) {
        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            String command = config.localBackendCommand.trim();
            if (command.endsWith(".cmd") || command.endsWith(".bat")) {
                return List.of(
                    "cmd.exe",
                    "/c",
                    command,
                    "--no-jinja",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    Integer.toString(config.localBackendPort),
                    "--model",
                    modelPath.toString(),
                    "--alias",
                    resolveAlias(config)
                );
            }
        }

        return List.of(
            runtimePath.toString(),
            "--no-jinja",
            "--host",
            "127.0.0.1",
            "--port",
            Integer.toString(config.localBackendPort),
            "--model",
            modelPath.toString(),
            "--alias",
            resolveAlias(config),
            "--ctx-size",
            "4096"
        );
    }

    private static String resolveAlias(ChatglotConfig config) {
        if (config.localModelAlias == null || config.localModelAlias.isBlank()) {
            return ChatglotConfig.LOCAL_BACKEND_DEFAULT_MODEL_ALIAS;
        }
        return config.localModelAlias.trim();
    }
}
