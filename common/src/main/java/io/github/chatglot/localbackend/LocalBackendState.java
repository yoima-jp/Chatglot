package io.github.chatglot.localbackend;

public final class LocalBackendState {
    public String backendVersion = "0.1.0";
    public int port = 17870;
    public Long pid;
    public String runtimePath = "";
    public String modelPath = "";
    public long lastKnownHealthyEpochMillis;
}
