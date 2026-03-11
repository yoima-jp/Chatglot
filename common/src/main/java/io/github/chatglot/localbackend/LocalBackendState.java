package io.github.chatglot.localbackend;

public final class LocalBackendState {
    public String backendVersion = "llama.cpp";
    public int port = 17870;
    public Long pid;
    public String runtimePath = "";
    public String executablePath = "";
    public String modelPath = "";
    public String downloadUrl = "";
    public long lastKnownHealthyEpochMillis;
}
