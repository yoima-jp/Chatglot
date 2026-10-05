package io.github.chatglot.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client-thread state for one open book screen. */
public final class BookTranslationState {
    private static final int CACHE_LIMIT = 128;
    private static final int PENDING_LIMIT = 4;
    private static final int MAX_TEXT_LENGTH = 4096;

    private final Map<Key, String> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, Request> pending = new HashMap<>();
    private final Map<Key, Throwable> failures = new LinkedHashMap<>(16, 0.75f, true);
    private long generation;
    private long nextRequestId;
    private Key currentPage;
    private Key busyPage;
    private boolean showingTranslations;

    public record Key(String text, String language, String provider) { }

    public record Request(Key key, long generation, long id) { }

    public enum Status { HIDDEN, LOADING, READY, FAILED, BUSY }

    /** Page changes update presentation only; they never start a translation. */
    public void observe(Key page) {
        currentPage = page;
    }

    /** Starts a user-requested translation or toggles a cached translation off. */
    public Request activate(Key page) {
        observe(page);
        if (page == null || page.text() == null || page.text().isBlank()
            || page.text().length() > MAX_TEXT_LENGTH) {
            return null;
        }

        busyPage = null;
        if (isTranslationVisible(page)) {
            showingTranslations = false;
            return null;
        }

        showingTranslations = true;
        if (cache.containsKey(page)) {
            failures.remove(page);
            return null;
        }
        if (pending.containsKey(page)) {
            return null;
        }

        failures.remove(page);
        if (pending.size() >= PENDING_LIMIT) {
            busyPage = page;
            return null;
        }

        Request request = new Request(page, generation, ++nextRequestId);
        pending.put(page, request);
        return request;
    }

    public boolean complete(Request request, String result, Throwable error) {
        if (request == null || request.generation() != generation
            || !request.equals(pending.get(request.key()))) {
            return false;
        }
        pending.remove(request.key());

        if (error == null && result != null && !result.isBlank() && result.length() <= MAX_TEXT_LENGTH) {
            cache.put(request.key(), result);
            failures.remove(request.key());
            if (cache.size() > CACHE_LIMIT) {
                cache.remove(cache.keySet().iterator().next());
            }
        } else {
            failures.put(request.key(), error != null ? error
                : new IllegalArgumentException("Translation result was empty or exceeded the size limit"));
            if (failures.size() > CACHE_LIMIT) {
                failures.remove(failures.keySet().iterator().next());
            }
        }
        return true;
    }

    public String displayedTranslation() {
        return displayedTranslation(currentPage);
    }

    public String displayedTranslation(Key page) {
        return isTranslationVisible(page) ? cache.get(page) : null;
    }

    public boolean isTranslationVisible(Key page) {
        return showingTranslations && page != null && cache.containsKey(page);
    }

    public boolean showingTranslations() {
        return showingTranslations;
    }

    public Status status() {
        return status(currentPage);
    }

    public Status status(Key page) {
        if (page == null) {
            return Status.HIDDEN;
        }
        if (isTranslationVisible(page)) {
            return Status.READY;
        }
        if (pending.containsKey(page)) {
            return Status.LOADING;
        }
        if (failures.containsKey(page)) {
            return Status.FAILED;
        }
        return page.equals(busyPage) ? Status.BUSY : Status.HIDDEN;
    }

    public Throwable failure(Key page) {
        return failures.get(page);
    }

    /** Invalidates outstanding callbacks as well as presentation and cached content. */
    public void invalidate() {
        generation++;
        pending.clear();
        cache.clear();
        failures.clear();
        currentPage = null;
        busyPage = null;
        showingTranslations = false;
    }
}
