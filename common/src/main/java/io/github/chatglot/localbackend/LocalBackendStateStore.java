package io.github.chatglot.localbackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalBackendStateStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/LocalBackendState");
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
                LOGGER.warn("state.json at {} was empty or malformed, returning defaults", path);
                loaded = new LocalBackendState();
            }
            return loaded;
        } catch (Exception e) {
            LOGGER.warn("Failed to load state.json from {}: {}", path, e.getMessage());
            return new LocalBackendState();
        }
    }

    public synchronized void save(LocalBackendState state) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(state));
    }
}
