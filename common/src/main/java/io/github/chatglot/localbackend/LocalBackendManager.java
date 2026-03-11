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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalBackendManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/LocalBackend");

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

    public CompletableFuture<Void> applyConfiguredBackendPolicyAsync(ChatglotConfig config) {
        return CompletableFuture.runAsync(() -> applyConfiguredBackendPolicy(config));
    }

    public void applyConfiguredBackendPolicy(ChatglotConfig config) {
        config.sanitize();
        cleanupOrphanedBackend(config);
        if (isLocalProviderSelected(config)) {
            ensureBackendAvailable(config);
            return;
        }
        stopManagedBackend(config);
    }

    public LocalBackendStatus checkStatus(ChatglotConfig config) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus(false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), "Unsupported OS. Local TranslateGemma is currently Windows-only.");
        }

        LocalBackendState state = new LocalBackendStateStore(sharedRoot).load();
        Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
        boolean healthy = healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds));
        boolean running = isProcessAlive(state.pid);
        boolean modelPresent = Files.exists(modelPath);
        String runtimeMessage = resolveRuntimeMessage(config);
        String message;
        if (healthy) {
            message = "Backend is healthy. " + runtimeMessage + " Model: " + modelPath;
        } else if (running) {
            message = "Backend is still starting. " + runtimeMessage + " Model: " + modelPath;
        } else if (!modelPresent) {
            message = "Backend is not reachable. " + runtimeMessage + " Model is missing: " + modelPath;
        } else {
            message = "Backend is not reachable. " + runtimeMessage + " Model: " + modelPath;
        }
        return new LocalBackendStatus(true, healthy, running, baseUrl, sharedRoot, modelPath.toString(), message);
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

        if (isProcessAlive(state.pid)) {
            return new LocalBackendStatus(
                true,
                false,
                true,
                baseUrl,
                sharedRoot,
                modelPath.toString(),
                "Backend is already starting. Please wait a few seconds and try again."
            );
        }

        progressListener.accept("Starting llama-server...");
        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(config, runtimePath, modelPath));
        processBuilder.directory(sharedRoot.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(LocalBackendPaths.logFile(sharedRoot).toFile()));
        Process process = processBuilder.start();
        state.pid = process.pid();
        state.ownerPid = ProcessHandle.current().pid();
        state.watcherPid = startWatcher(state.ownerPid, state.pid);
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
        cleanupOrphanedBackend(config);
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

    public void stopManagedBackend(ChatglotConfig config) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        LocalBackendStateStore store = new LocalBackendStateStore(sharedRoot);
        LocalBackendState state = store.load();
        long currentPid = ProcessHandle.current().pid();

        if (state.ownerPid != null && state.ownerPid == currentPid && state.pid != null) {
            stopProcess(state.pid);
        }
        if (state.watcherPid != null) {
            stopProcess(state.watcherPid);
        }

        state.pid = null;
        state.ownerPid = null;
        state.watcherPid = null;
        try {
            store.save(state);
        } catch (IOException e) {
            LOGGER.warn("Failed to save local backend state during shutdown: {}", e.getMessage());
        }
    }

    public void cleanupOrphanedBackend(ChatglotConfig config) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendSharedDirectory);
        LocalBackendStateStore store = new LocalBackendStateStore(sharedRoot);
        LocalBackendState state = store.load();
        if (state.pid == null || state.ownerPid == null) {
            return;
        }

        if (isProcessAlive(state.ownerPid)) {
            return;
        }

        LOGGER.info("Cleaning up orphaned local backend process. ownerPid={} backendPid={}", state.ownerPid, state.pid);
        stopProcess(state.pid);
        if (state.watcherPid != null) {
            stopProcess(state.watcherPid);
        }
        state.pid = null;
        state.ownerPid = null;
        state.watcherPid = null;
        try {
            store.save(state);
        } catch (IOException e) {
            LOGGER.warn("Failed to save local backend state after orphan cleanup: {}", e.getMessage());
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
        if (!installer.isRuntimeReady(config)) {
            return new LocalBackendStatus(
                true,
                false,
                false,
                baseUrl,
                sharedRoot,
                LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(),
                "Run setup first. Setup installs the local runtime required before downloading or repairing the model."
            );
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

    private static boolean isLocalProviderSelected(ChatglotConfig config) {
        return "translategemma_local".equalsIgnoreCase(config.provider);
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
            "--no-warmup",
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

    private static Long startWatcher(long ownerPid, long childPid) {
        if (!isWindows()) {
            return null;
        }

        String script =
            "$owner=" + ownerPid + ";"
                + "$child=" + childPid + ";"
                + "while (Get-Process -Id $owner -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 2 }; "
                + "Stop-Process -Id $child -Force -ErrorAction SilentlyContinue";
        try {
            Process watcher = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-WindowStyle",
                "Hidden",
                "-Command",
                script
            ).start();
            return watcher.pid();
        } catch (IOException e) {
            LOGGER.warn("Failed to start local backend watcher: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isProcessAlive(Long pid) {
        if (pid == null) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void stopProcess(Long pid) {
        if (pid == null) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(process -> {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        });
    }
}
