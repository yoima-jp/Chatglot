package io.github.chatglot.localbackend;

public record LocalBackendStatus(boolean healthy, String message, String backendUrl, String sharedDir, String modelPath) {
}
