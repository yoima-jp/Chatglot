package io.github.chatglot.translation.provider.codex;

import com.google.gson.JsonObject;

public record CodexAuthTokens(
    String accessToken,
    String refreshToken,
    String idToken,
    String accountId,
    long expiresAtEpochSeconds
) {
    public boolean isFresh(long nowEpochSeconds) {
        return accessToken != null && !accessToken.isBlank() && expiresAtEpochSeconds > nowEpochSeconds + 30;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("access_token", emptyToNull(accessToken));
        json.addProperty("refresh_token", emptyToNull(refreshToken));
        json.addProperty("id_token", emptyToNull(idToken));
        json.addProperty("account_id", emptyToNull(accountId));
        json.addProperty("expires_at", expiresAtEpochSeconds);
        return json;
    }

    public static CodexAuthTokens fromJson(JsonObject json) {
        return new CodexAuthTokens(
            read(json, "access_token"),
            read(json, "refresh_token"),
            read(json, "id_token"),
            read(json, "account_id"),
            readLong(json, "expires_at")
        );
    }

    private static String read(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    private static long readLong(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return json.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
