package io.github.chatglot.localbackend;

import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import java.io.IOException;
import java.nio.file.Path;

public final class LocalBackendProcess {
    public Process start(ChatglotConfig config, Path sharedRoot, LocalBackendState state) throws IOException, TranslationException {
        String commandTemplate = config.translategemmaLocalBackendCommand == null
            ? ""
            : config.translategemmaLocalBackendCommand.trim();
        if (commandTemplate.isBlank()) {
            throw new TranslationException("Backend launch command is empty. Configure it in Local TranslateGemma settings.");
        }

        String command = commandTemplate
            .replace("{port}", Integer.toString(config.translategemmaLocalBackendPort))
            .replace("{model}", quoteArg(resolveModelPath(config, state)));

        Path runtimeDir = LocalBackendPaths.runtimeDir(sharedRoot);
        Path logsFile = LocalBackendPaths.logsDir(sharedRoot).resolve("backend.log");

        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command)
            .directory(runtimeDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logsFile.toFile()));

        return builder.start();
    }

    private static String resolveModelPath(ChatglotConfig config, LocalBackendState state) {
        if (config.translategemmaLocalModelPath != null && !config.translategemmaLocalModelPath.isBlank()) {
            return config.translategemmaLocalModelPath.trim();
        }
        if (state.modelPath != null && !state.modelPath.isBlank()) {
            return state.modelPath.trim();
        }
        return "";
    }

    private static String quoteArg(String value) {
        if (value.isBlank()) {
            return "\"\"";
        }
        if (value.contains(" ")) {
            return '"' + value + '"';
        }
        return value;
    }
}
