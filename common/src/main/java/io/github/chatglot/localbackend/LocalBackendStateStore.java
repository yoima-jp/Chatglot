package io.github.chatglot.localbackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendStateStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path path;

    public LocalBackendStateStore(Path sharedRoot) {
        this.path = LocalBackendPaths.stateFile(sharedRoot);
    }

    public synchronized LocalBackendState load() {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                LocalBackendState defaults = new LocalBackendState();
                save(defaults);
                return defaults;
            }

            LocalBackendState loaded = GSON.fromJson(Files.readString(path), LocalBackendState.class);
            if (loaded == null) {
                loaded = new LocalBackendState();
            }
            return loaded;
        } catch (Exception e) {
            return new LocalBackendState();
        }
    }

    public synchronized void save(LocalBackendState state) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(state));
    }
}
