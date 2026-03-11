package io.github.chatglot.translation.localbackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalBackendState {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public String backendVersion = "v1";
    public int port = 28100;
    public Long pid;
    public String runtimePath = "";
    public String modelPath = "";
    public long lastKnownHealthyTimestamp;

    public static LocalBackendState load(Path stateFile) {
        try {
            if (!Files.exists(stateFile)) {
                return new LocalBackendState();
            }
            LocalBackendState loaded = GSON.fromJson(Files.readString(stateFile), LocalBackendState.class);
            if (loaded == null) {
                return new LocalBackendState();
            }
            return loaded;
        } catch (Exception ignored) {
            return new LocalBackendState();
        }
    }

    public void save(Path stateFile) throws IOException {
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, GSON.toJson(this));
    }
}
