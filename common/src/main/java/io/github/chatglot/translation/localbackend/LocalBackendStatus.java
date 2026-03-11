package io.github.chatglot.translation.localbackend;

public record LocalBackendStatus(boolean healthy, String message, String backendUrl) {
}
