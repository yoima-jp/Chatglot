package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class LocalBackendManager {
    private final LocalBackendInstaller installer = new LocalBackendInstaller();
    private final LocalBackendStateStore stateStore = new LocalBackendStateStore();
    private final LocalBackendProcess processStarter = new LocalBackendProcess();
    private final LocalBackendHealthChecker healthChecker = new LocalBackendHealthChecker();

    public synchronized String setupAndEnsureRunning(ChatglotConfig config) throws Exception {
        ensureWindows();
        Path sharedRoot = installer.prepareDirectories(config);
        LocalBackendState state = stateStore.load(sharedRoot);
        applyConfigToState(config, sharedRoot, state);
        stateStore.save(sharedRoot, state);

        String baseUrl = resolveBaseUrl(config);
        if (healthChecker.isHealthy(baseUrl, Math.min(config.requestTimeoutSeconds, 8))) {
            state.lastKnownHealthyEpochMillis = System.currentTimeMillis();
            stateStore.save(sharedRoot, state);
            return "Backend already running at " + baseUrl;
        }

        if (state.pid != null) {
            ProcessHandle.of(state.pid).ifPresent(ProcessHandle::destroy);
        }

        Process process = processStarter.start(config, sharedRoot, state);
        state.pid = process.pid();
        stateStore.save(sharedRoot, state);

        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (healthChecker.isHealthy(baseUrl, 2)) {
                state.lastKnownHealthyEpochMillis = System.currentTimeMillis();
                stateStore.save(sharedRoot, state);
                return "Backend started successfully at " + baseUrl;
            }
            if (!process.isAlive()) {
                throw new TranslationException("Backend process exited early. Check logs in " + LocalBackendPaths.logsDir(sharedRoot));
            }
            Thread.sleep(500L);
        }

        throw new TranslationException("Backend startup timed out. Check logs in " + LocalBackendPaths.logsDir(sharedRoot));
    }

    public synchronized String checkStatus(ChatglotConfig config) throws Exception {
        ensureWindows();
        Path sharedRoot = LocalBackendPaths.resolveSharedRoot(config);
        if (!Files.exists(LocalBackendPaths.stateFile(sharedRoot))) {
            return "Not set up yet. Run setup first.";
        }

        LocalBackendState state = stateStore.load(sharedRoot);
        String baseUrl = resolveBaseUrl(config);
        boolean healthy = healthChecker.isHealthy(baseUrl, Math.min(config.requestTimeoutSeconds, 6));
        String pidText = state.pid == null ? "n/a" : Long.toString(state.pid);
        String healthyText = state.lastKnownHealthyEpochMillis <= 0
            ? "never"
            : Instant.ofEpochMilli(state.lastKnownHealthyEpochMillis).toString();

        if (healthy) {
            state.lastKnownHealthyEpochMillis = System.currentTimeMillis();
            stateStore.save(sharedRoot, state);
            return "Healthy. URL=" + baseUrl + ", pid=" + pidText + ", lastHealthy=" + healthyText;
        }
        return "Unhealthy. URL=" + baseUrl + ", pid=" + pidText + ", lastHealthy=" + healthyText;
    }

    private static void applyConfigToState(ChatglotConfig config, Path sharedRoot, LocalBackendState state) {
        state.port = config.translategemmaLocalBackendPort;
        state.runtimePath = LocalBackendPaths.runtimeDir(sharedRoot).toString();
        if (config.translategemmaLocalModelPath != null && !config.translategemmaLocalModelPath.isBlank()) {
            state.modelPath = config.translategemmaLocalModelPath.trim();
        }
        state.backendVersion = "v1";
    }

    private static String resolveBaseUrl(ChatglotConfig config) {
        if (config.translategemmaLocalBackendUrl != null && !config.translategemmaLocalBackendUrl.isBlank()) {
            return trimTrailingSlash(config.translategemmaLocalBackendUrl.trim());
        }
        return "http://127.0.0.1:" + config.translategemmaLocalBackendPort;
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void ensureWindows() throws TranslationException {
        if (!LocalBackendPaths.isWindows()) {
            throw new TranslationException("Local TranslateGemma backend is currently supported on Windows only.");
        }
    }
}
