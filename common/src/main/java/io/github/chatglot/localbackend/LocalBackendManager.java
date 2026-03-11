package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
            return new LocalBackendStatus(false, false, false, baseUrl, sharedRoot, config.localModelPath, "Unsupported OS. Local TranslateGemma is currently Windows-only.");
        }

        boolean healthy = healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds));
        return new LocalBackendStatus(true, healthy, healthy, baseUrl, sharedRoot, config.localModelPath,
            healthy ? "Backend is healthy." : "Backend is not reachable. Run setup or start your backend manually.");
    }

    public LocalBackendStatus setupAndStart(ChatglotConfig config) throws IOException {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus(false, false, false, baseUrl, sharedRoot, config.localModelPath, "Unsupported OS. Local TranslateGemma is currently Windows-only.");
        }

        installer.ensureLayout(sharedRoot);
        installer.ensureRuntimePlaceholder(sharedRoot);

        LocalBackendStateStore store = new LocalBackendStateStore(sharedRoot);
        LocalBackendState state = store.load();
        state.port = config.localBackendPort;
        state.runtimePath = LocalBackendPaths.runtimeDir(sharedRoot).toString();
        state.modelPath = config.localModelPath == null ? "" : config.localModelPath.trim();

        if (healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds))) {
            state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
            store.save(state);
            return new LocalBackendStatus(true, true, true, baseUrl, sharedRoot, state.modelPath, "Backend already running and healthy.");
        }

        LocalBackendStatus started = tryStartBackend(config, sharedRoot, baseUrl, state, store);
        if (started.healthy()) {
            return started;
        }

        return new LocalBackendStatus(true, false, false, baseUrl, sharedRoot, state.modelPath,
            started.message() + " Model download is manual in this version; set a valid model path and install a compatible local backend.");
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

    private LocalBackendStatus tryStartBackend(
        ChatglotConfig config,
        Path sharedRoot,
        String baseUrl,
        LocalBackendState state,
        LocalBackendStateStore store
    ) throws IOException {
        String modelPath = config.localModelPath == null ? "" : config.localModelPath.trim();
        if (modelPath.isBlank()) {
            store.save(state);
            return new LocalBackendStatus(true, false, false, baseUrl, sharedRoot, modelPath, "Model path is empty. Set a local model path first.");
        }

        Path commandPath = resolveCommandPath(config, sharedRoot);
        if (!java.nio.file.Files.exists(commandPath)) {
            store.save(state);
            return new LocalBackendStatus(true, false, false, baseUrl, sharedRoot, modelPath,
                "Backend runtime command not found: " + commandPath);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
            commandPath.toString(),
            "--port",
            Integer.toString(config.localBackendPort),
            "--model",
            modelPath
        );
        processBuilder.directory(LocalBackendPaths.runtimeDir(sharedRoot).toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(LocalBackendPaths.logsDir(sharedRoot).resolve("backend.log").toFile());

        Process process = processBuilder.start();
        state.pid = process.pid();
        store.save(state);

        long startedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() - startedAt < 15_000) {
            if (healthChecker.isHealthy(baseUrl, 2)) {
                state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
                store.save(state);
                return new LocalBackendStatus(true, true, true, baseUrl, sharedRoot, modelPath, "Backend started and healthy.");
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

        return new LocalBackendStatus(true, false, process.isAlive(), baseUrl, sharedRoot, modelPath,
            "Backend started but health check failed. Check logs/backend.log.");
    }

    private static Path resolveCommandPath(ChatglotConfig config, Path sharedRoot) {
        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            return Path.of(config.localBackendCommand.trim());
        }
        return LocalBackendPaths.runtimeDir(sharedRoot).resolve("chatglot-local-backend.cmd");
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
}
