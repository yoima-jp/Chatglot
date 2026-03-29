package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.config.ChatglotStoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalBackendManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/LocalBackend");

    private final Path configDir;
    private final LocalBackendInstaller installer = new LocalBackendInstaller();
    private final LocalBackendHealthChecker healthChecker = new LocalBackendHealthChecker();

    public LocalBackendManager(Path configDir) {
        this.configDir = configDir;
    }

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
        cleanupKnownBackends(config);
        if (isLocalProviderSelected(config)) {
            stopManagedBackendsExceptCurrent(config);
            ensureBackendAvailable(config);
            return;
        }
        stopAllManagedBackends(config);
    }

    public LocalBackendStatus checkStatus(ChatglotConfig config) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config, configDir);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus("unsupported_os", false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.unsupportedOs());
        }

        LocalBackendState state = new LocalBackendStateStore(sharedRoot).load();
        Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
        boolean healthy = healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds));
        boolean running = isProcessAlive(state.pid);
        boolean modelPresent = Files.exists(modelPath);
        Component runtimeMessage = resolveRuntimeMessage(config, sharedRoot);
        Component message;
        if (healthy) {
            message = LocalBackendTexts.backendHealthy(runtimeMessage, modelPath.toString());
        } else if (running) {
            message = LocalBackendTexts.backendStarting(runtimeMessage, modelPath.toString());
        } else if (!modelPresent) {
            message = LocalBackendTexts.backendNotReachableMissingModel(runtimeMessage, modelPath.toString());
        } else {
            message = LocalBackendTexts.backendNotReachable(runtimeMessage, modelPath.toString());
        }
        return new LocalBackendStatus("status", true, healthy, running, baseUrl, sharedRoot, modelPath.toString(), message);
    }

    public LocalBackendStatus setupAndStart(ChatglotConfig config) throws IOException {
        return setupAndStart(config, message -> {
        });
    }

    public LocalBackendStatus setupAndStart(ChatglotConfig config, Consumer<Component> progressListener) throws IOException {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config, configDir);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus("unsupported_os", false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.unsupportedOs());
        }

        progressListener.accept(LocalBackendTexts.preparingFolders());
        installer.ensureLayout(sharedRoot);
        installer.ensureRuntimePlaceholder(sharedRoot);

        Path runtimePath = installer.resolveInstalledRuntime(config, sharedRoot);
        if (runtimePath == null) {
            return new LocalBackendStatus(
                "runtime_missing",
                true,
                false,
                false,
                baseUrl,
                sharedRoot,
                LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(),
                LocalBackendTexts.runtimeMissing()
            );
        }

        Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
        if (!Files.exists(modelPath) || Files.size(modelPath) <= 0) {
            return new LocalBackendStatus(
                "model_missing",
                true,
                false,
                false,
                baseUrl,
                sharedRoot,
                modelPath.toString(),
                LocalBackendTexts.modelMissing()
            );
        }

        LocalBackendStateStore store = new LocalBackendStateStore(sharedRoot);
        LocalBackendState state = store.load();
        List<String> desiredCommand = buildCommand(config, runtimePath, modelPath);
        String desiredLaunchSignature = buildLaunchSignature(desiredCommand);
        state.port = config.localBackendPort;
        state.parallelRequests = resolveParallelRequests(config);
        state.runtimePath = runtimePath.getParent() == null ? sharedRoot.toString() : runtimePath.getParent().toString();
        state.executablePath = runtimePath.toString();
        state.modelPath = modelPath.toString();
        state.downloadUrl = config.localModelDownloadUrl == null ? "" : config.localModelDownloadUrl.trim();
        state.launchSignature = desiredLaunchSignature;

        if (healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds))) {
            if (managedBackendMatchesConfig(state, runtimePath, modelPath, desiredLaunchSignature)) {
                state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
                store.save(state);
                return new LocalBackendStatus("backend_already_healthy", true, true, true, baseUrl, sharedRoot, state.modelPath, LocalBackendTexts.backendAlreadyHealthy(runtimePath.toString(), modelPath.toString()));
            }

            stopManagedBackend(sharedRoot);
            state = store.load();
            state.port = config.localBackendPort;
            state.parallelRequests = resolveParallelRequests(config);
            state.runtimePath = runtimePath.getParent() == null ? sharedRoot.toString() : runtimePath.getParent().toString();
            state.executablePath = runtimePath.toString();
            state.modelPath = modelPath.toString();
            state.downloadUrl = config.localModelDownloadUrl == null ? "" : config.localModelDownloadUrl.trim();
            state.launchSignature = desiredLaunchSignature;
            if (healthChecker.isHealthy(baseUrl, Math.min(10, config.requestTimeoutSeconds))) {
                state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
                store.save(state);
                return new LocalBackendStatus("backend_already_healthy", true, true, true, baseUrl, sharedRoot, state.modelPath, LocalBackendTexts.backendAlreadyHealthy(runtimePath.toString(), modelPath.toString()));
            }
        }

        if (isProcessAlive(state.pid)) {
            return new LocalBackendStatus(
                "backend_already_starting",
                true,
                false,
                true,
                baseUrl,
                sharedRoot,
                modelPath.toString(),
                LocalBackendTexts.backendAlreadyStarting()
            );
        }

        progressListener.accept(LocalBackendTexts.startingServer());
        ProcessBuilder processBuilder = new ProcessBuilder(desiredCommand);
        processBuilder.directory(sharedRoot.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(LocalBackendPaths.logFile(sharedRoot).toFile()));
        Process process = processBuilder.start();
        state.pid = process.pid();
        state.ownerPid = ProcessHandle.current().pid();
        state.watcherPid = startWatcher(state.ownerPid, state.pid);
        store.save(state);

        long startedAt = System.currentTimeMillis();
        progressListener.accept(LocalBackendTexts.waitingHealthCheck());
        while (System.currentTimeMillis() - startedAt < 15000) {
            if (healthChecker.isHealthy(baseUrl, 2)) {
                state.lastKnownHealthyEpochMillis = Instant.now().toEpochMilli();
                store.save(state);
                return new LocalBackendStatus("backend_started_healthy", true, true, true, baseUrl, sharedRoot, modelPath.toString(), LocalBackendTexts.backendStartedHealthy(runtimePath.toString(), modelPath.toString()));
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

        return new LocalBackendStatus("backend_healthcheck_failed", true, false, process.isAlive(), baseUrl, sharedRoot, modelPath.toString(), LocalBackendTexts.backendStartedHealthCheckFailed(LocalBackendPaths.logFile(sharedRoot).toString()));
    }

    public LocalBackendStatus ensureBackendAvailable(ChatglotConfig config) {
        cleanupKnownBackends(config);
        stopManagedBackendsExceptCurrent(config);
        LocalBackendStatus status = checkStatus(config);
        if (status.healthy()) {
            return status;
        }
        try {
            return setupAndStart(config);
        } catch (Exception e) {
            return new LocalBackendStatus("backend_start_failed", status.supported(), false, false, status.backendUrl(), status.sharedRoot(), status.modelPath(), LocalBackendTexts.backendStartFailed(e.getMessage()));
        }
    }

    public void stopManagedBackend(ChatglotConfig config) {
        stopManagedBackend(LocalBackendPaths.resolveSharedRoot(config, configDir));
    }

    public void stopManagedBackend(Path sharedRoot) {
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
        cleanupOrphanedBackend(LocalBackendPaths.resolveSharedRoot(config, configDir));
    }

    public void cleanupOrphanedBackend(Path sharedRoot) {
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

    public LocalBackendStatus downloadAllAndStart(ChatglotConfig config) {
        return downloadAllAndStart(config, message -> {
        });
    }

    public LocalBackendStatus downloadAllAndStart(ChatglotConfig config, Consumer<Component> progressListener) {
        LocalBackendStatus runtimeStatus = downloadRuntime(config, progressListener);
        if ("runtime_download_failed".equals(runtimeStatus.code()) || "runtime_override_missing".equals(runtimeStatus.code())) {
            return runtimeStatus;
        }

        LocalBackendStatus modelStatus = downloadModel(config, progressListener);
        if ("model_download_failed".equals(modelStatus.code()) || "runtime_missing".equals(modelStatus.code())) {
            return modelStatus;
        }

        try {
            progressListener.accept(LocalBackendTexts.startingAfterDownloads());
            return setupAndStart(config, progressListener);
        } catch (Exception e) {
            return new LocalBackendStatus(
                "backend_start_after_downloads_failed",
                modelStatus.supported(),
                false,
                false,
                modelStatus.backendUrl(),
                modelStatus.sharedRoot(),
                modelStatus.modelPath(),
                LocalBackendTexts.backendStartAfterDownloadsFailed(e.getMessage())
            );
        }
    }

    public LocalBackendStatus downloadModel(ChatglotConfig config) {
        return downloadModel(config, message -> {
        });
    }

    public LocalBackendStatus downloadRuntime(ChatglotConfig config) {
        return downloadRuntime(config, message -> {
        });
    }

    public LocalBackendStatus downloadRuntime(ChatglotConfig config, Consumer<Component> progressListener) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config, configDir);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus("unsupported_os", false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.unsupportedOs());
        }

        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            Path override = Path.of(config.localBackendCommand.trim());
            if (Files.exists(override)) {
                return new LocalBackendStatus("runtime_override_configured", true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.runtimeOverrideConfigured(override.toString()));
            }
            return new LocalBackendStatus("runtime_override_missing", true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.runtimeOverrideMissing(override.toString()));
        }

        try {
            progressListener.accept(LocalBackendTexts.preparingFolders());
            installer.ensureLayout(sharedRoot);
            installer.ensureRuntimePlaceholder(sharedRoot);
            Path runtimePath = installer.ensureRuntime(config, sharedRoot, progressListener);
            return new LocalBackendStatus("runtime_ready", true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.runtimeReady(runtimePath.toString()));
        } catch (Exception e) {
            return new LocalBackendStatus("runtime_download_failed", true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.runtimeDownloadFailed(e.getMessage()));
        }
    }

    public LocalBackendStatus downloadModel(ChatglotConfig config, Consumer<Component> progressListener) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config, configDir);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus("unsupported_os", false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.unsupportedOs());
        }
        try {
            progressListener.accept(LocalBackendTexts.preparingFolders());
            installer.ensureLayout(sharedRoot);
            installer.ensureRuntimePlaceholder(sharedRoot);
            if (!installer.isRuntimeReady(config, sharedRoot)) {
                return new LocalBackendStatus(
                    "runtime_missing",
                    true,
                    false,
                    false,
                    baseUrl,
                    sharedRoot,
                    LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(),
                    LocalBackendTexts.runtimeMissing()
                );
            }
            Path modelPath = installer.ensureModelDownloaded(config, sharedRoot, progressListener);
            return new LocalBackendStatus("model_ready", true, false, false, baseUrl, sharedRoot, modelPath.toString(), LocalBackendTexts.modelReady(modelPath.toString()));
        } catch (Exception e) {
            return new LocalBackendStatus("model_download_failed", true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.modelDownloadFailed(e.getMessage()));
        }
    }

    public LocalBackendStatus reinstallModel(ChatglotConfig config) {
        return reinstallModel(config, message -> {
        });
    }

    public LocalBackendStatus reinstallModel(ChatglotConfig config, Consumer<Component> progressListener) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config, configDir);
        String baseUrl = resolveBaseUrl(config);
        if (!isWindows()) {
            return new LocalBackendStatus("unsupported_os", false, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.unsupportedOs());
        }
        try {
            progressListener.accept(LocalBackendTexts.preparingFolders());
            installer.ensureLayout(sharedRoot);
            installer.ensureRuntimePlaceholder(sharedRoot);
            if (!installer.isRuntimeReady(config, sharedRoot)) {
                return new LocalBackendStatus(
                    "runtime_missing",
                    true,
                    false,
                    false,
                    baseUrl,
                    sharedRoot,
                    LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(),
                    LocalBackendTexts.runtimeMissing()
                );
            }
            Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
            Files.deleteIfExists(modelPath);
            Files.deleteIfExists(modelPath.resolveSibling(modelPath.getFileName() + ".part"));
            progressListener.accept(LocalBackendTexts.removedExistingModel(modelPath.toString()));
            Path downloadedModel = installer.ensureModelDownloaded(config, sharedRoot, progressListener);
            return new LocalBackendStatus("model_reinstalled", true, false, false, baseUrl, sharedRoot, downloadedModel.toString(), LocalBackendTexts.modelReinstalled(downloadedModel.toString()));
        } catch (Exception e) {
            return new LocalBackendStatus("model_reinstall_failed", true, false, false, baseUrl, sharedRoot, LocalBackendPaths.resolveModelPath(config, sharedRoot).toString(), LocalBackendTexts.modelReinstallFailed(e.getMessage()));
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

    private void cleanupKnownBackends(ChatglotConfig config) {
        for (Path root : collectKnownManagedRoots(config)) {
            cleanupOrphanedBackend(root);
        }
    }

    private void stopAllManagedBackends(ChatglotConfig config) {
        for (Path root : collectKnownManagedRoots(config)) {
            stopManagedBackend(root);
        }
    }

    private void stopManagedBackendsExceptCurrent(ChatglotConfig config) {
        Path currentRoot = LocalBackendPaths.resolveSharedRoot(config, configDir);
        for (Path root : collectKnownManagedRoots(config)) {
            if (!root.equals(currentRoot)) {
                stopManagedBackend(root);
            }
        }
    }

    private Set<Path> collectKnownManagedRoots(ChatglotConfig config) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(LocalBackendPaths.resolveSharedRoot(config, configDir));
        roots.add(ChatglotStoragePaths.resolveModConfigRoot(configDir).resolve("local-backend"));
        roots.add(ChatglotStoragePaths.resolveLegacyLocalBackendRoot());
        return roots;
    }

    private Component resolveRuntimeMessage(ChatglotConfig config, Path sharedRoot) {
        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            return LocalBackendTexts.runtimeMessage(Path.of(config.localBackendCommand.trim()).toString());
        }

        Path runtimePath = installer.resolveInstalledRuntime(config, sharedRoot);
        if (installer.isRuntimeReady(config, sharedRoot)) {
            return LocalBackendTexts.runtimeMessage(runtimePath.toString());
        }
        return LocalBackendTexts.runtimeNotReady(LocalBackendPaths.runtimeDir(sharedRoot).toString());
    }

    private static List<String> buildCommand(ChatglotConfig config, Path runtimePath, Path modelPath) {
        int parallelRequests = resolveParallelRequests(config);
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
                    resolveAlias(config),
                    "--parallel",
                    Integer.toString(parallelRequests)
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
            "--parallel",
            Integer.toString(parallelRequests),
            "--ctx-size",
            "4096"
        );
    }

    private static int resolveParallelRequests(ChatglotConfig config) {
        return Math.max(1, config.maxConcurrentTranslations);
    }

    private static String buildLaunchSignature(List<String> command) {
        return String.join("\u001F", command);
    }

    private static boolean managedBackendMatchesConfig(
        LocalBackendState state,
        Path runtimePath,
        Path modelPath,
        String desiredLaunchSignature
    ) {
        long currentPid = ProcessHandle.current().pid();
        if (state.ownerPid == null || state.ownerPid != currentPid || state.pid == null || !isProcessAlive(state.pid)) {
            return false;
        }
        return Objects.equals(state.executablePath, runtimePath.toString())
            && Objects.equals(state.modelPath, modelPath.toString())
            && Objects.equals(state.launchSignature, desiredLaunchSignature);
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
