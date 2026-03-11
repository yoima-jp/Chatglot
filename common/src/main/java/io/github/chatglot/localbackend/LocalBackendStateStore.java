package io.github.chatglot.localbackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendStateStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path statePath;

    public LocalBackendStateStore(Path statePath) {
        this.statePath = statePath;
    }

    public synchronized LocalBackendState load() {
        try {
            Files.createDirectories(statePath.getParent());
            if (!Files.exists(statePath)) {
                LocalBackendState defaults = new LocalBackendState();
                save(defaults);
                return defaults;
            }

            LocalBackendState loaded = GSON.fromJson(Files.readString(statePath), LocalBackendState.class);
            if (loaded == null) {
                return new LocalBackendState();
            }
            sanitize(loaded);
            return loaded;
        } catch (Exception ignored) {
            return new LocalBackendState();
        }
    }

    public synchronized void save(LocalBackendState state) throws IOException {
        sanitize(state);
        Files.createDirectories(statePath.getParent());
        Files.writeString(statePath, GSON.toJson(state));
    }

    private static void sanitize(LocalBackendState state) {
        if (state.backendVersion == null || state.backendVersion.isBlank()) {
            state.backendVersion = "0.1.0";
        }
        if (state.port <= 0 || state.port > 65535) {
            state.port = LocalBackendState.DEFAULT_PORT;
        }
        if (state.runtimePath == null) {
            state.runtimePath = "";
        }
        if (state.modelPath == null) {
            state.modelPath = "";
        }
        if (state.lastKnownHealthyTimestamp < 0) {
            state.lastKnownHealthyTimestamp = 0L;
        }
    }
}
