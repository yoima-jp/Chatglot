package io.github.chatglot.client;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Client-thread state; a fresh instance isolates each world session from late responses. */
public final class SignTranslationState {
    private static final long LOOK_AWAY_DELAY_MS = 800;
    private static final int CACHE_LIMIT = 128;
    private static final int PENDING_LIMIT = 4;
    private final Map<Key, String> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Key> pending = new HashSet<>();
    private Target target;
    private long lastLookTime;
    private Status status = Status.HIDDEN;
    private String translation = "";
    private Throwable failure;
    private String failureProviderId;

    public record Key(String text, String language, String provider) { }

    public record Target(int x, int y, int z, boolean front, Key key) { }

    public enum Status { HIDDEN, LOADING, READY, FAILED, BUSY }

    public void observe(Target observed, long now) {
        if (target == null) {
            return;
        }
        if (target.equals(observed)) {
            lastLookTime = now;
        } else if (observed != null || now - lastLookTime >= LOOK_AWAY_DELAY_MS) {
            hide();
        }
    }

    public boolean request(Target requested, long now) {
        if (requested == null || requested.key().text().isBlank()) {
            return false;
        }
        target = requested;
        lastLookTime = now;
        translation = cache.getOrDefault(requested.key(), "");
        if (!translation.isEmpty()) {
            failure = null;
            failureProviderId = null;
            status = Status.READY;
            return false;
        }
        if (pending.contains(requested.key())) {
            status = Status.LOADING;
            return false;
        }
        if (pending.size() >= PENDING_LIMIT) {
            status = Status.BUSY;
            return false;
        }
        pending.add(requested.key());
        failure = null;
        failureProviderId = null;
        status = Status.LOADING;
        return true;
    }

    public void complete(Key key, String result) {
        complete(key, result, null);
    }

    public void complete(Key key, String result, Throwable error) {
        if (!pending.remove(key)) {
            return;
        }
        boolean success = result != null && !result.isBlank() && result.length() <= 4096;
        if (success) {
            cache.put(key, result);
            if (cache.size() > CACHE_LIMIT) {
                cache.remove(cache.keySet().iterator().next());
            }
        }
        // A response may populate the cache, but must never reopen a dismissed panel.
        if (target != null && target.key().equals(key)) {
            translation = success ? result : "";
            failure = success ? null : error;
            failureProviderId = success ? null : key.provider();
            status = success ? Status.READY : Status.FAILED;
        }
    }

    public void hide() {
        target = null;
        status = Status.HIDDEN;
        translation = "";
        failure = null;
        failureProviderId = null;
    }

    public Status status() {
        return status;
    }

    public String translation() {
        return translation;
    }

    public Throwable failure() {
        return failure;
    }

    public String failureProviderId() {
        return failureProviderId;
    }
}
