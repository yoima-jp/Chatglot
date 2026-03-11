package io.github.chatglot.localbackend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LocalBackendProcess {
    private Process process;

    public synchronized Process start(Path sharedRoot, int port, String modelPath) throws IOException {
        if (process != null && process.isAlive()) {
            return process;
        }

        Path runtimeExe = LocalBackendPaths.runtimeDir(sharedRoot).resolve("chatglot-local-backend.exe");
        if (!runtimeExe.toFile().exists()) {
            throw new IOException("Backend executable is missing: " + runtimeExe);
        }

        List<String> command = new ArrayList<>();
        command.add(runtimeExe.toString());
        command.add("--port");
        command.add(Integer.toString(port));
        if (modelPath != null && !modelPath.isBlank()) {
            command.add("--model");
            command.add(modelPath.trim());
        }

        Path logsDir = LocalBackendPaths.logsDir(sharedRoot);
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(LocalBackendPaths.runtimeDir(sharedRoot).toFile())
            .redirectOutput(logsDir.resolve("backend.out.log").toFile())
            .redirectError(logsDir.resolve("backend.err.log").toFile());

        process = builder.start();
        return process;
    }

    public synchronized void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        process = null;
    }

    public synchronized Long currentPid() {
        if (process != null && process.isAlive()) {
            return process.pid();
        }
        return null;
    }
}
