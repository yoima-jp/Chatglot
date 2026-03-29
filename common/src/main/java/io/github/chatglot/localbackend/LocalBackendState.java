package io.github.chatglot.localbackend;

public final class LocalBackendState {
    public String backendVersion = "llama.cpp";
    public int port = 17870;
    public int parallelRequests = 1;
    public Long pid;
    public Long ownerPid;
    public Long watcherPid;
    public String runtimePath = "";
    public String executablePath = "";
    public String modelPath = "";
    public String downloadUrl = "";
    public String launchSignature = "";
    public long lastKnownHealthyEpochMillis;
}
