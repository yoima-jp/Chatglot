package io.github.chatglot.localbackend;

public final class LocalBackendState {
    public String backendVersion = "v1";
    public int port = 17495;
    public Long pid = null;
    public String runtimePath = "";
    public String modelPath = "";
    public long lastKnownHealthyEpochMillis = 0L;
}
