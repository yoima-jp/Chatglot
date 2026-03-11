package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.nio.file.Path;

public final class LocalBackendManager {
    private final LocalBackendInstaller installer = new LocalBackendInstaller();
    private final LocalBackendProcess process = new LocalBackendProcess();
    private final LocalBackendHealthChecker healthChecker = new LocalBackendHealthChecker();

    public synchronized LocalBackendStatus setupAndStart(ChatglotConfig config) {
        if (!LocalBackendPaths.isWindows()) {
            return status(config, false, "Local TranslateGemma backend is currently supported on Windows only.");
        }

        try {
            Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendInstallDir);
            installer.ensureInstalled(sharedRoot);
            LocalBackendStateStore stateStore = new LocalBackendStateStore(LocalBackendPaths.stateFile(sharedRoot));
            LocalBackendState state = stateStore.load();
            state.runtimePath = LocalBackendPaths.runtimeDir(sharedRoot).toString();
            state.modelPath = normalizeModelPath(config, sharedRoot);
            state.port = resolvePort(config, state.port);
            stateStore.save(state);

            String backendUrl = resolveBackendUrl(config, state.port);
            if (!healthChecker.isHealthy(backendUrl, config.requestTimeoutSeconds)) {
                process.start(sharedRoot, state.port, state.modelPath);
                Thread.sleep(1200L);
            }

            boolean healthy = healthChecker.isHealthy(backendUrl, config.requestTimeoutSeconds);
            state.pid = process.currentPid();
            if (healthy) {
                state.lastKnownHealthyTimestamp = System.currentTimeMillis();
            }
            stateStore.save(state);
            return status(config, healthy, healthy ? "Backend is healthy." : "Backend setup completed, but health check failed.");
        } catch (Exception e) {
            return status(config, false, "Local backend setup failed: " + e.getMessage());
        }
    }

    public synchronized LocalBackendStatus checkStatus(ChatglotConfig config) {
        if (!LocalBackendPaths.isWindows()) {
            return status(config, false, "Local TranslateGemma backend is currently supported on Windows only.");
        }

        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendInstallDir);
        LocalBackendStateStore stateStore = new LocalBackendStateStore(LocalBackendPaths.stateFile(sharedRoot));
        LocalBackendState state = stateStore.load();
        int port = resolvePort(config, state.port);
        String backendUrl = resolveBackendUrl(config, port);
        boolean healthy = healthChecker.isHealthy(backendUrl, config.requestTimeoutSeconds);
        if (healthy) {
            state.lastKnownHealthyTimestamp = System.currentTimeMillis();
            state.pid = process.currentPid();
            try {
                stateStore.save(state);
            } catch (IOException ignored) {
            }
        }

        return status(config, healthy, healthy ? "Backend is running." : "Backend is not reachable.");
    }

    public synchronized LocalBackendStatus restart(ChatglotConfig config) {
        process.stop();
        return setupAndStart(config);
    }

    public synchronized void stop() {
        process.stop();
    }

    public synchronized void ensureBackendAvailable(ChatglotConfig config) throws IOException {
        LocalBackendStatus status = checkStatus(config);
        if (status.healthy()) {
            return;
        }

        LocalBackendStatus started = setupAndStart(config);
        if (!started.healthy()) {
            throw new IOException(started.message());
        }
    }

    private static String normalizeModelPath(ChatglotConfig config, Path sharedRoot) {
        if (config.localBackendModelPath != null && !config.localBackendModelPath.isBlank()) {
            return config.localBackendModelPath.trim();
        }
        return LocalBackendPaths.modelsDir(sharedRoot).toString();
    }

    private static int resolvePort(ChatglotConfig config, int fallbackPort) {
        if (config.localBackendPort > 0 && config.localBackendPort <= 65535) {
            return config.localBackendPort;
        }
        return fallbackPort > 0 ? fallbackPort : LocalBackendState.DEFAULT_PORT;
    }

    private static String resolveBackendUrl(ChatglotConfig config, int port) {
        if (config.localBackendUrl != null && !config.localBackendUrl.isBlank()) {
            return config.localBackendUrl.trim();
        }
        return "http://127.0.0.1:" + port;
    }

    private static LocalBackendStatus status(ChatglotConfig config, boolean healthy, String message) {
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config.localBackendInstallDir);
        String backendUrl = resolveBackendUrl(config, resolvePort(config, LocalBackendState.DEFAULT_PORT));
        return new LocalBackendStatus(
            healthy,
            message,
            backendUrl,
            sharedRoot.toString(),
            normalizeModelPath(config, sharedRoot)
        );
    }
}
