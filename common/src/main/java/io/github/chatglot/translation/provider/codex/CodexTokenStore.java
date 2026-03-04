package io.github.chatglot.translation.provider.codex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.translation.TranslationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CodexTokenStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public CodexAuthTokens read(Path path) throws TranslationException {
        if (!Files.exists(path)) {
            return null;
        }

        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new TranslationException("Codex token file is invalid JSON object: " + path);
            }
            return CodexAuthTokens.fromJson(parsed.getAsJsonObject());
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("Failed to read Codex token file: " + path, e);
        }
    }

    public void write(Path path, CodexAuthTokens tokens) throws TranslationException {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject json = tokens.toJson();
            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
            tightenPermissions(path);
        } catch (Exception e) {
            throw new TranslationException("Failed to write Codex token file: " + path, e);
        }
    }

    private static void tightenPermissions(Path path) {
        try {
            path.toFile().setReadable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(false, false);
            path.toFile().setWritable(true, true);
        } catch (Exception ignored) {
            // Best effort only.
        }
    }
}
