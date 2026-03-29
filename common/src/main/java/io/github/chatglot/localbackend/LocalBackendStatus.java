package io.github.chatglot.localbackend;

import java.nio.file.Path;
import net.minecraft.network.chat.Component;

public record LocalBackendStatus(
    String code,
    boolean supported,
    boolean healthy,
    boolean running,
    String backendUrl,
    Path sharedRoot,
    String modelPath,
    Component message
) {}
