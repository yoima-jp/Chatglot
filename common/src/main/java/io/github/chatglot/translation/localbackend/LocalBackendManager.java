package io.github.chatglot.translation.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import java.io.IOException;
import java.time.Instant;

public final class LocalBackendManager {
    private final Object lock = new Object();
    private final LocalBackendInstaller installer = new LocalBackendInstaller();
    private final LocalBackendProcess localBackendProcess = new LocalBackendProcess();
    private final LocalBackendHealthChecker healthChecker = new LocalBackendHealthChecker();
    private Process currentProcess;

    public LocalBackendStatus setupAndStart(ChatglotConfig config) {
        synchronized (lock) {
            if (!isWindows()) {
                return new LocalBackendStatus(false, "Local TranslateGemma is currently supported only on Windows.", "");
            }

            LocalBackendPaths paths = LocalBackendPaths.fromConfig(config);
            String backendUrl = resolveBackendUrl(config);
            try {
                installer.ensureInstalled(paths);
                LocalBackendState state = LocalBackendState.load(paths.stateFile());
                updateStateFromConfig(state, config, paths);
                state.save(paths.stateFile());

                if (healthChecker.isHealthy(backendUrl, config.requestTimeoutSeconds)) {
                    state.lastKnownHealthyTimestamp = Instant.now().toEpochMilli();
                    state.save(paths.stateFile());
                    return new LocalBackendStatus(true, "Backend is already running and healthy.", backendUrl);
                }

                restartProcessIfOwned();
                currentProcess = localBackendProcess.start(config, paths);
                boolean healthy = waitForHealth(backendUrl, config.requestTimeoutSeconds);
                state.pid = currentProcess.pid();
                if (healthy) {
                    state.lastKnownHealthyTimestamp = Instant.now().toEpochMilli();
                    state.save(paths.stateFile());
                    return new LocalBackendStatus(true, "Backend started successfully.", backendUrl);
                }

                state.save(paths.stateFile());
                return new LocalBackendStatus(false, "Backend started but health check failed. See logs/backend.log.", backendUrl);
            } catch (Exception e) {
                return new LocalBackendStatus(false, "Setup/start failed: " + e.getMessage(), backendUrl);
            }
        }
    }

    public LocalBackendStatus checkStatus(ChatglotConfig config) {
        synchronized (lock) {
            if (!isWindows()) {
                return new LocalBackendStatus(false, "Local TranslateGemma is currently supported only on Windows.", "");
            }
            String backendUrl = resolveBackendUrl(config);
            boolean healthy = healthChecker.isHealthy(backendUrl, config.requestTimeoutSeconds);
            LocalBackendPaths paths = LocalBackendPaths.fromConfig(config);
            try {
                LocalBackendState state = LocalBackendState.load(paths.stateFile());
                if (healthy) {
                    state.lastKnownHealthyTimestamp = Instant.now().toEpochMilli();
                    updateStateFromConfig(state, config, paths);
                    state.save(paths.stateFile());
                    return new LocalBackendStatus(true, "Backend health check succeeded.", backendUrl);
                }
                return new LocalBackendStatus(false, "Backend is not reachable.", backendUrl);
            } catch (IOException e) {
                return new LocalBackendStatus(healthy, "Backend checked, but state save failed: " + e.getMessage(), backendUrl);
            }
        }
    }

    public void ensureBackendReadyOrThrow(ChatglotConfig config) throws TranslationException {
        LocalBackendStatus status = checkStatus(config);
        if (status.healthy()) {
            return;
        }

        LocalBackendStatus setupStatus = setupAndStart(config);
        if (!setupStatus.healthy()) {
            throw new TranslationException(setupStatus.message());
        }
    }

    public LocalBackendStatus stop(ChatglotConfig config) {
        synchronized (lock) {
            restartProcessIfOwned();
            return checkStatus(config);
        }
    }

    private void restartProcessIfOwned() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
            try {
                currentProcess.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        currentProcess = null;
    }

    private static void updateStateFromConfig(LocalBackendState state, ChatglotConfig config, LocalBackendPaths paths) {
        state.backendVersion = "v1";
        state.port = config.translategemmaLocalPort;
        state.runtimePath = paths.runtimeDir().toString();
        state.modelPath = config.translategemmaLocalModelPath == null ? "" : config.translategemmaLocalModelPath.trim();
    }

    private static String resolveBackendUrl(ChatglotConfig config) {
        String configured = config.translategemmaLocalBackendUrl == null ? "" : config.translategemmaLocalBackendUrl.trim();
        if (configured.isBlank()) {
            return "http://127.0.0.1:" + config.translategemmaLocalPort;
        }
        return configured;
    }

    private static boolean waitForHealth(String backendUrl, int timeoutSeconds) {
        LocalBackendHealthChecker checker = new LocalBackendHealthChecker();
        int attempts = Math.max(5, timeoutSeconds * 2);
        for (int i = 0; i < attempts; i++) {
            if (checker.isHealthy(backendUrl, Math.max(2, timeoutSeconds))) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
