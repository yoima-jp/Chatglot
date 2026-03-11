package io.github.chatglot.translation.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LocalBackendProcess {
    public Process start(ChatglotConfig config, LocalBackendPaths paths) throws IOException {
        String launcher = config.translategemmaLocalLauncherCommand == null ? "" : config.translategemmaLocalLauncherCommand.trim();
        if (launcher.isBlank()) {
            throw new IOException("Launcher command is empty. Set translategemmaLocalLauncherCommand in Chatglot settings.");
        }

        List<String> command = tokenize(launcher);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(resolveWorkingDir(paths).toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logsDir().resolve("backend.log").toFile()));

        if (config.translategemmaLocalModelPath != null && !config.translategemmaLocalModelPath.isBlank()) {
            processBuilder.environment().put("CHATGLOT_MODEL_PATH", config.translategemmaLocalModelPath.trim());
        }
        processBuilder.environment().put("CHATGLOT_PORT", Integer.toString(config.translategemmaLocalPort));

        return processBuilder.start();
    }

    private static Path resolveWorkingDir(LocalBackendPaths paths) {
        return paths.runtimeDir();
    }

    private static List<String> tokenize(String commandLine) {
        String[] tokens = commandLine.trim().split("\\s+");
        List<String> command = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (!token.isBlank()) {
                command.add(token);
            }
        }
        return command;
    }
}
