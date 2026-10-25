package io.github.chatglot.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Minecraft-independent request and preview state for translating chat input. */
public final class ChatInputTranslationState {
    public static final int CHAT_LIMIT = 256;
    public static final int RECENT_LANGUAGE_LIMIT = 5;

    private long latestRequestId;
    private Request request;
    private String currentText;
    private String currentLanguage;
    private String translation;
    private Throwable failure;
    private boolean tooLong;

    public record Request(long id, String text, String language, String provider) { }

    public enum Status { HIDDEN, LOADING, READY, FAILED }

    public static boolean canTranslate(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return !text.strip().startsWith("/");
    }

    public Request start(String text, String language, String provider) {
        observe(text, language);
        if (!canTranslate(text)) {
            return null;
        }
        request = new Request(++latestRequestId, text, language, provider);
        translation = null;
        failure = null;
        tooLong = false;
        return request;
    }

    /** Clears a preview as soon as either the original input or its destination changes. */
    public void observe(String text, String language) {
        currentText = text;
        currentLanguage = language;
        if (request != null && (!Objects.equals(request.text(), text)
            || !Objects.equals(request.language(), language))) {
            invalidateCurrent();
        }
    }

    public boolean complete(Request completed, String result, Throwable error) {
        if (completed == null || request == null || completed.id() != latestRequestId
            || !completed.equals(request)
            || !Objects.equals(completed.text(), currentText)
            || !Objects.equals(completed.language(), currentLanguage)) {
            return false;
        }
        if (error != null) {
            failure = error;
            return true;
        }
        if (result == null || result.isBlank()) {
            failure = new IllegalArgumentException("Translation result was empty");
            return true;
        }
        translation = result;
        tooLong = result.length() > CHAT_LIMIT;
        return true;
    }

    public boolean canApply(String text, String language, String provider) {
        return status() == Status.READY && !tooLong && request != null
            && request.id() == latestRequestId
            && Objects.equals(request.text(), text)
            && Objects.equals(request.language(), language)
            && Objects.equals(request.provider(), provider)
            && canTranslate(text);
    }

    public boolean isLatest(Request candidate) {
        return candidate != null && request != null && candidate.id() == latestRequestId
            && candidate.equals(request);
    }

    public Status status() {
        if (request == null) {
            return Status.HIDDEN;
        }
        if (failure != null) {
            return Status.FAILED;
        }
        return translation == null ? Status.LOADING : Status.READY;
    }

    public String translation() {
        return translation;
    }

    public Throwable failure() {
        return failure;
    }

    public boolean tooLong() {
        return tooLong;
    }

    public Request request() {
        return request;
    }

    public void discard() {
        invalidateCurrent();
    }

    /** Invalidates callbacks when the screen is removed or rebuilt. */
    public void invalidate() {
        latestRequestId++;
        request = null;
        translation = null;
        failure = null;
        tooLong = false;
        currentText = null;
        currentLanguage = null;
    }

    public static List<String> updateRecentLanguages(List<String> recent, String language) {
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        if (language != null && !language.isBlank()) {
            updated.add(normalizeLanguage(language));
        }
        if (recent != null) {
            for (String entry : recent) {
                if (entry != null && !entry.isBlank()) {
                    updated.add(normalizeLanguage(entry));
                    if (updated.size() == RECENT_LANGUAGE_LIMIT) {
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(updated);
    }

    private void invalidateCurrent() {
        latestRequestId++;
        request = null;
        translation = null;
        failure = null;
        tooLong = false;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().replace('-', '_').toLowerCase(java.util.Locale.ROOT);
    }
}
