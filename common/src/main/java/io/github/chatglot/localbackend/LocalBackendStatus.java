package io.github.chatglot.localbackend;

import java.nio.file.Path;

public record LocalBackendStatus(
    boolean supported,
    boolean healthy,
    boolean running,
    String backendUrl,
    Path sharedRoot,
    String modelPath,
    String message
) {}
