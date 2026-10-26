package io.github.chatglot.translation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistent LRU of successful translations. Credentials stay out of the disk key so secrets are never persisted. */
public final class TranslationCache {
    public static final int DEFAULT_MAX_ENTRIES = 5000;
    public static final int MIN_MAX_ENTRIES = ChatglotConfig.TRANSLATION_CACHE_MIN_ENTRIES;
    public static final int MAX_MAX_ENTRIES = ChatglotConfig.TRANSLATION_CACHE_MAX_ENTRIES;

    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/TranslationCache");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final long WRITE_DEBOUNCE_MILLIS = 750;

    private final Path file;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ScheduledThreadPoolExecutor writer;
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();
    private ScheduledFuture<?> pendingWrite;
    private int maxEntries;
    private boolean closed;

    public TranslationCache(Path file, int maxEntries) {
        this.file = file;
        this.maxEntries = clampMaxEntries(maxEntries);
        this.writer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "chatglot-translation-cache");
            thread.setDaemon(true);
            return thread;
        });
        writer.setRemoveOnCancelPolicy(true);
        load();
    }

    public synchronized Optional<Entry> get(Key key, int requestedMaxEntries) {
        updateMaximum(requestedMaxEntries);
        Entry entry = entries.get(key);
        if (entry != null) {
            queueWrite();
        }
        return Optional.ofNullable(entry);
    }

    public synchronized void put(Key key, Entry entry, int requestedMaxEntries) {
        if (closed || key == null || entry == null || entry.translatedText() == null || entry.translatedText().isBlank()) {
            return;
        }
        updateMaximum(requestedMaxEntries);
        entries.put(key, entry);
        trimToLimit();
        queueWrite();
    }

    public synchronized void setMaxEntries(int requestedMaxEntries) {
        updateMaximum(requestedMaxEntries);
    }

    public synchronized void clear() {
        if (closed) {
            return;
        }
        entries.clear();
        queueWrite();
    }

    public synchronized int size() {
        return entries.size();
    }

    public void flush() {
        Future<?> write;
        synchronized (this) {
            if (closed) {
                return;
            }
            cancelPendingWrite();
            try {
                write = writer.submit(this::writeSnapshot);
            } catch (RejectedExecutionException ignored) {
                return;
            }
        }
        await(write);
    }

    public void shutdown() {
        Future<?> write;
        boolean owner;
        synchronized (this) {
            if (closed) {
                write = null;
                owner = false;
            } else {
                closed = true;
                cancelPendingWrite();
                write = writer.submit(this::writeSnapshot);
                owner = true;
            }
        }
        if (!owner) {
            await(shutdownComplete);
            return;
        }
        await(write);
        writer.shutdown();
        shutdownComplete.complete(null);
    }

    public static int clampMaxEntries(int value) {
        return Math.max(MIN_MAX_ENTRIES, Math.min(MAX_MAX_ENTRIES, value));
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")
                || !root.getAsJsonObject().get("entries").isJsonArray()) {
                throw new IllegalArgumentException("Invalid cache file shape");
            }
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("entries")) {
                JsonObject object = element.getAsJsonObject();
                Key key = new Key(
                    stringValue(object, "provider"),
                    stringValue(object, "source"),
                    stringValue(object, "target"),
                    stringValue(object, "text"),
                    stringValue(object, "settings")
                );
                Entry entry = new Entry(
                    stringValue(object, "translated"),
                    stringValue(object, "detectedSource"),
                    longValue(object, "createdAt")
                );
                if (!entry.translatedText().isBlank()) {
                    entries.put(key, entry);
                }
            }
            int previousSize = entries.size();
            trimToLimit();
            if (entries.size() != previousSize) {
                queueWrite();
            }
        } catch (Exception error) {
            entries.clear();
            LOGGER.warn("Ignoring unreadable translation cache at {}: {}", file, error.getMessage());
            queueWrite();
        }
    }

    private void updateMaximum(int requestedMaxEntries) {
        int normalized = clampMaxEntries(requestedMaxEntries);
        if (normalized != maxEntries) {
            maxEntries = normalized;
            int previousSize = entries.size();
            trimToLimit();
            if (entries.size() != previousSize) {
                queueWrite();
            }
        }
    }

    private void trimToLimit() {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void queueWrite() {
        if (closed) {
            return;
        }
        cancelPendingWrite();
        pendingWrite = writer.schedule(this::writeSnapshot, WRITE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void cancelPendingWrite() {
        if (pendingWrite != null) {
            pendingWrite.cancel(false);
            pendingWrite = null;
        }
    }

    private void writeSnapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray serializedEntries = new JsonArray();
        synchronized (this) {
            for (Map.Entry<Key, Entry> item : entries.entrySet()) {
                JsonObject object = new JsonObject();
                object.addProperty("provider", item.getKey().providerId());
                object.addProperty("source", item.getKey().sourceLanguageHint());
                object.addProperty("target", item.getKey().targetLanguage());
                object.addProperty("text", item.getKey().text());
                object.addProperty("settings", item.getKey().settingsFingerprint());
                object.addProperty("translated", item.getValue().translatedText());
                object.addProperty("detectedSource", item.getValue().detectedSourceLanguage());
                object.addProperty("createdAt", item.getValue().createdAtMillis());
                serializedEntries.add(object);
            }
        }
        root.add("entries", serializedEntries);

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            LOGGER.warn("Could not write translation cache at {}: {}", file, error.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next successful atomic replacement overwrites this temporary file.
            }
        }
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static long longValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0L;
    }

    private static void await(Future<?> task) {
        try {
            task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException error) {
            LOGGER.warn("Could not flush translation cache: {}", error.getCause().getMessage());
        }
    }

    public record Key(
        String providerId,
        String sourceLanguageHint,
        String targetLanguage,
        String text,
        String settingsFingerprint
    ) { }

    public record Entry(String translatedText, String detectedSourceLanguage, long createdAtMillis) { }
}
