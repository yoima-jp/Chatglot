package io.github.chatglot.localbackend;

import net.minecraft.text.Text;

public final class LocalBackendTexts {
    private LocalBackendTexts() {
    }

    public static Text unsupportedOs() {
        return Text.translatable("chatglot.local_backend.unsupported_os");
    }

    public static Text backendHealthy(Text runtimeMessage, String modelPath) {
        return Text.translatable("chatglot.local_backend.backend_healthy", runtimeMessage, modelPath);
    }

    public static Text backendStarting(Text runtimeMessage, String modelPath) {
        return Text.translatable("chatglot.local_backend.backend_starting", runtimeMessage, modelPath);
    }

    public static Text backendNotReachableMissingModel(Text runtimeMessage, String modelPath) {
        return Text.translatable("chatglot.local_backend.backend_not_reachable_missing_model", runtimeMessage, modelPath);
    }

    public static Text backendNotReachable(Text runtimeMessage, String modelPath) {
        return Text.translatable("chatglot.local_backend.backend_not_reachable", runtimeMessage, modelPath);
    }

    public static Text preparingFolders() {
        return Text.translatable("chatglot.local_backend.progress.preparing_folders");
    }

    public static Text runtimeMissing() {
        return Text.translatable("chatglot.local_backend.runtime_missing");
    }

    public static Text modelMissing() {
        return Text.translatable("chatglot.local_backend.model_missing");
    }

    public static Text backendAlreadyHealthy(String runtimePath, String modelPath) {
        return Text.translatable("chatglot.local_backend.backend_already_healthy", runtimePath, modelPath);
    }

    public static Text backendAlreadyStarting() {
        return Text.translatable("chatglot.local_backend.backend_already_starting");
    }

    public static Text startingServer() {
        return Text.translatable("chatglot.local_backend.progress.starting_server");
    }

    public static Text waitingHealthCheck() {
        return Text.translatable("chatglot.local_backend.progress.waiting_health_check");
    }

    public static Text backendStartedHealthy(String runtimePath, String modelPath) {
        return Text.translatable("chatglot.local_backend.backend_started_healthy", runtimePath, modelPath);
    }

    public static Text backendStartedHealthCheckFailed(String logFile) {
        return Text.translatable("chatglot.local_backend.backend_started_healthcheck_failed", logFile);
    }

    public static Text backendStartFailed(String error) {
        return Text.translatable("chatglot.local_backend.backend_start_failed", error);
    }

    public static Text startingAfterDownloads() {
        return Text.translatable("chatglot.local_backend.progress.start_after_downloads");
    }

    public static Text backendStartAfterDownloadsFailed(String error) {
        return Text.translatable("chatglot.local_backend.backend_start_after_downloads_failed", error);
    }

    public static Text runtimeOverrideConfigured(String path) {
        return Text.translatable("chatglot.local_backend.runtime_override_configured", path);
    }

    public static Text runtimeOverrideMissing(String path) {
        return Text.translatable("chatglot.local_backend.runtime_override_missing", path);
    }

    public static Text runtimeReady(String path) {
        return Text.translatable("chatglot.local_backend.runtime_ready", path);
    }

    public static Text runtimeDownloadFailed(String error) {
        return Text.translatable("chatglot.local_backend.runtime_download_failed", error);
    }

    public static Text modelReady(String path) {
        return Text.translatable("chatglot.local_backend.model_ready", path);
    }

    public static Text modelDownloadFailed(String error) {
        return Text.translatable("chatglot.local_backend.model_download_failed", error);
    }

    public static Text removedExistingModel(String path) {
        return Text.translatable("chatglot.local_backend.progress.removed_model", path);
    }

    public static Text modelReinstalled(String path) {
        return Text.translatable("chatglot.local_backend.model_reinstalled", path);
    }

    public static Text modelReinstallFailed(String error) {
        return Text.translatable("chatglot.local_backend.model_reinstall_failed", error);
    }

    public static Text runtimeMessage(String path) {
        return Text.translatable("chatglot.local_backend.runtime_path", path);
    }

    public static Text runtimeNotReady(String path) {
        return Text.translatable("chatglot.local_backend.runtime_not_ready", path);
    }

    public static Text autoTranslateTemporarilyDisabled() {
        return Text.translatable("chatglot.local_backend.progress.auto_translate_disabled");
    }

    public static Text usingRuntimeOverride(String path) {
        return Text.translatable("chatglot.local_backend.progress.using_runtime_override", path);
    }

    public static Text usingManagedRuntime(String path) {
        return Text.translatable("chatglot.local_backend.progress.using_managed_runtime", path);
    }

    public static Text downloadingManagedRuntime() {
        return Text.translatable("chatglot.local_backend.progress.downloading_runtime");
    }

    public static Text downloadedManagedRuntime(String path) {
        return Text.translatable("chatglot.local_backend.progress.downloaded_runtime", path);
    }

    public static Text modelAlreadyPresent(String path) {
        return Text.translatable("chatglot.local_backend.progress.model_already_present", path);
    }

    public static Text downloadingModel(String url) {
        return Text.translatable("chatglot.local_backend.progress.downloading_model_from", url);
    }

    public static Text modelDownloadFinished(String path) {
        return Text.translatable("chatglot.local_backend.progress.model_download_finished", path);
    }

    public static Text downloadProgress(String label, String percent, String downloaded, String total) {
        return Text.translatable("chatglot.local_backend.progress.download_percent", label, percent, downloaded, total);
    }

    public static Text downloadProgressUnknown(String label, String downloaded) {
        return Text.translatable("chatglot.local_backend.progress.download_bytes", label, downloaded);
    }
}
