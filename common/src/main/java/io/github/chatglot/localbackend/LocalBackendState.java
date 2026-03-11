package io.github.chatglot.localbackend;

public final class LocalBackendState {
    public static final int DEFAULT_PORT = 47834;

    public String backendVersion = "0.1.0";
    public int port = DEFAULT_PORT;
    public Long pid;
    public String runtimePath = "";
    public String modelPath = "";
    public long lastKnownHealthyTimestamp = 0L;
}
