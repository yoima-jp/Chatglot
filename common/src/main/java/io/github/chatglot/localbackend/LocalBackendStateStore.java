package io.github.chatglot.localbackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendStateStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public LocalBackendState load(Path sharedRoot) throws IOException {
        Path stateFile = LocalBackendPaths.stateFile(sharedRoot);
        if (!Files.exists(stateFile)) {
            LocalBackendState created = new LocalBackendState();
            save(sharedRoot, created);
            return created;
        }

        LocalBackendState loaded = GSON.fromJson(Files.readString(stateFile), LocalBackendState.class);
        if (loaded == null) {
            loaded = new LocalBackendState();
        }
        return loaded;
    }

    public void save(Path sharedRoot, LocalBackendState state) throws IOException {
        Path stateFile = LocalBackendPaths.stateFile(sharedRoot);
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, GSON.toJson(state));
    }
}
