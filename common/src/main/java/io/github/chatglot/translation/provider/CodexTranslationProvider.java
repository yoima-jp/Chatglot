package io.github.chatglot.translation.provider;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class CodexTranslationProvider implements TranslationProvider {
    private static final String RESOURCE_SCRIPT_PATH = "/assets/chatglot/scripts/codex_auth_call.py";

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public TranslationResult translate(TranslationRequest request, ChatglotConfig config, Path configDir, Path gameDir)
        throws TranslationException {
        Path scriptPath = resolveScriptPath(config, configDir, gameDir);
        Path tokenFile = resolveTokenFile(config, configDir);
        String prompt = buildPrompt(request);

        List<String> command = new ArrayList<>(splitCommand(config.codexPythonCommand));
        command.add(scriptPath.toString());
        command.add("--prompt");
        command.add(prompt);
        command.add("--model");
        command.add(config.codexModel);
        command.add("--effort");
        command.add(config.codexReasoningEffort);
        command.add("--summary");
        command.add(config.codexReasoningSummary);
        command.add("--token-file");
        command.add(tokenFile.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(config.requestTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TranslationException("Codex process timed out after " + config.requestTimeoutSeconds + " seconds.");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new TranslationException("Codex process failed (exit=" + process.exitValue() + "): " + abbreviate(output, 1000));
            }

            if (output.isBlank()) {
                throw new TranslationException("Codex process returned empty output.");
            }

            return new TranslationResult(output, request.sourceLanguageHint(), id());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Failed to execute Codex python bridge", e);
        }
    }

    private static Path resolveScriptPath(ChatglotConfig config, Path configDir, Path gameDir) throws TranslationException {
        if (config.codexScriptPath != null && !config.codexScriptPath.isBlank()) {
            Path configuredPath = Path.of(config.codexScriptPath.trim());
            if (!Files.exists(configuredPath)) {
                throw new TranslationException("Configured Codex script path does not exist: " + configuredPath);
            }
            return configuredPath;
        }

        Path workspaceDefault = gameDir.resolve("codex_auth_call.py");
        if (Files.exists(workspaceDefault)) {
            return workspaceDefault;
        }

        Path extracted = configDir.resolve(ChatglotConstants.MOD_ID).resolve("codex_auth_call.py");
        if (!Files.exists(extracted)) {
            extractBundledScript(extracted);
        }
        return extracted;
    }

    private static Path resolveTokenFile(ChatglotConfig config, Path configDir) {
        if (config.codexTokenFile != null && !config.codexTokenFile.isBlank()) {
            return Path.of(config.codexTokenFile.trim());
        }
        return configDir.resolve(ChatglotConstants.MOD_ID).resolve("codex_tokens.json");
    }

    private static void extractBundledScript(Path destination) throws TranslationException {
        try {
            Files.createDirectories(destination.getParent());
            try (InputStream in = CodexTranslationProvider.class.getResourceAsStream(RESOURCE_SCRIPT_PATH)) {
                if (in == null) {
                    throw new TranslationException("Bundled Codex script resource is missing: " + RESOURCE_SCRIPT_PATH);
                }
                Files.write(destination, in.readAllBytes());
            }
        } catch (IOException e) {
            throw new TranslationException("Failed to extract bundled Codex script", e);
        }
    }

    private static String buildPrompt(TranslationRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("Translate the following Minecraft chat message into ")
            .append(request.targetLanguage())
            .append(". ")
            .append("Preserve player names, commands, URLs, placeholders, and formatting markers when possible. ")
            .append("Return only translated text without explanations.");

        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            builder.append(" Source language hint: ").append(request.sourceLanguageHint()).append('.');
        }

        builder.append("\n\nMessage:\n").append(request.text());
        return builder.toString();
    }

    private static List<String> splitCommand(String command) throws TranslationException {
        if (command == null || command.isBlank()) {
            throw new TranslationException("codexPythonCommand is empty.");
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (char c : command.toCharArray()) {
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (inSingle || inDouble) {
            throw new TranslationException("codexPythonCommand has unbalanced quotes.");
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }

        if (parts.isEmpty()) {
            throw new TranslationException("codexPythonCommand did not produce an executable command.");
        }

        return parts;
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
