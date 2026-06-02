package io.github.chatglot.localbackend;

import net.minecraft.network.chat.Component;

public final class LocalBackendTexts {
    private LocalBackendTexts() {
    }

    public static Component unsupportedOs() {
        return Component.translatable("chatglot.local_backend.unsupported_os");
    }

    public static Component backendHealthy(Component runtimeMessage, String modelPath) {
        return Component.translatable("chatglot.local_backend.backend_healthy", runtimeMessage, modelPath);
    }

    public static Component backendStarting(Component runtimeMessage, String modelPath) {
        return Component.translatable("chatglot.local_backend.backend_starting", runtimeMessage, modelPath);
    }

    public static Component backendNotReachableMissingModel(Component runtimeMessage, String modelPath) {
        return Component.translatable("chatglot.local_backend.backend_not_reachable_missing_model", runtimeMessage, modelPath);
    }

    public static Component backendNotReachable(Component runtimeMessage, String modelPath) {
        return Component.translatable("chatglot.local_backend.backend_not_reachable", runtimeMessage, modelPath);
    }

    public static Component backendNotReachableWithError(Component runtimeMessage, String modelPath, String error) {
        return Component.translatable("chatglot.local_backend.backend_not_reachable_with_error", runtimeMessage, modelPath, error);
    }

    public static Component preparingFolders() {
        return Component.translatable("chatglot.local_backend.progress.preparing_folders");
    }

    public static Component runtimeMissing() {
        return Component.translatable("chatglot.local_backend.runtime_missing");
    }

    public static Component modelMissing() {
        return Component.translatable("chatglot.local_backend.model_missing");
    }

    public static Component backendAlreadyHealthy(String runtimePath, String modelPath) {
        return Component.translatable("chatglot.local_backend.backend_already_healthy", runtimePath, modelPath);
    }

    public static Component backendAlreadyStarting() {
        return Component.translatable("chatglot.local_backend.backend_already_starting");
    }

    public static Component startingServer() {
        return Component.translatable("chatglot.local_backend.progress.starting_server");
    }

    public static Component waitingHealthCheck() {
        return Component.translatable("chatglot.local_backend.progress.waiting_health_check");
    }

    public static Component backendStartedHealthy(String runtimePath, String modelPath) {
        return Component.translatable("chatglot.local_backend.backend_started_healthy", runtimePath, modelPath);
    }

    public static Component backendStartedHealthCheckFailed(String logFile) {
        return Component.translatable("chatglot.local_backend.backend_started_healthcheck_failed", logFile);
    }

    public static Component backendStartedHealthCheckFailed(String logFile, String errorDetail) {
        return Component.translatable("chatglot.local_backend.backend_started_healthcheck_failed_with_error", logFile, errorDetail);
    }

    public static Component backendStartFailed(String error) {
        return Component.translatable("chatglot.local_backend.backend_start_failed", error);
    }

    public static Component startingAfterDownloads() {
        return Component.translatable("chatglot.local_backend.progress.start_after_downloads");
    }

    public static Component backendStartAfterDownloadsFailed(String error) {
        return Component.translatable("chatglot.local_backend.backend_start_after_downloads_failed", error);
    }

    public static Component runtimeOverrideConfigured(String path) {
        return Component.translatable("chatglot.local_backend.runtime_override_configured", path);
    }

    public static Component runtimeOverrideMissing(String path) {
        return Component.translatable("chatglot.local_backend.runtime_override_missing", path);
    }

    public static Component runtimeReady(String path) {
        return Component.translatable("chatglot.local_backend.runtime_ready", path);
    }

    public static Component runtimeDownloadFailed(String error) {
        return Component.translatable("chatglot.local_backend.runtime_download_failed", error);
    }

    public static Component modelReady(String path) {
        return Component.translatable("chatglot.local_backend.model_ready", path);
    }

    public static Component modelDownloadFailed(String error) {
        return Component.translatable("chatglot.local_backend.model_download_failed", error);
    }

    public static Component removedExistingModel(String path) {
        return Component.translatable("chatglot.local_backend.progress.removed_model", path);
    }

    public static Component modelReinstalled(String path) {
        return Component.translatable("chatglot.local_backend.model_reinstalled", path);
    }

    public static Component modelReinstallFailed(String error) {
        return Component.translatable("chatglot.local_backend.model_reinstall_failed", error);
    }

    public static Component runtimeMessage(String path) {
        return Component.translatable("chatglot.local_backend.runtime_path", path);
    }

    public static Component runtimeNotReady(String path) {
        return Component.translatable("chatglot.local_backend.runtime_not_ready", path);
    }

    public static Component autoTranslateTemporarilyDisabled() {
        return Component.translatable("chatglot.local_backend.progress.auto_translate_disabled");
    }

    public static Component usingRuntimeOverride(String path) {
        return Component.translatable("chatglot.local_backend.progress.using_runtime_override", path);
    }

    public static Component usingManagedRuntime(String path) {
        return Component.translatable("chatglot.local_backend.progress.using_managed_runtime", path);
    }

    public static Component downloadingManagedRuntime() {
        return Component.translatable("chatglot.local_backend.progress.downloading_runtime");
    }

    public static Component downloadedManagedRuntime(String path) {
        return Component.translatable("chatglot.local_backend.progress.downloaded_runtime", path);
    }

    public static Component modelAlreadyPresent(String path) {
        return Component.translatable("chatglot.local_backend.progress.model_already_present", path);
    }

    public static Component downloadingModel(String url) {
        return Component.translatable("chatglot.local_backend.progress.downloading_model_from", url);
    }

    public static Component modelDownloadFinished(String path) {
        return Component.translatable("chatglot.local_backend.progress.model_download_finished", path);
    }

    public static Component downloadProgress(String label, String percent, String downloaded, String total) {
        return Component.translatable("chatglot.local_backend.progress.download_percent", label, percent, downloaded, total);
    }

    public static Component downloadProgressUnknown(String label, String downloaded) {
        return Component.translatable("chatglot.local_backend.progress.download_bytes", label, downloaded);
    }
}
