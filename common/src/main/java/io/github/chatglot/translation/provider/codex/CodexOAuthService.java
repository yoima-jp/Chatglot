package io.github.chatglot.translation.provider.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.translation.TranslationException;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexOAuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodexOAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String ISSUER = "https://auth.openai.com";
    private static final String OAUTH_SCOPE = "openid profile email offline_access";
    private static final int OAUTH_PORT = 1455;
    private static final int OAUTH_TIMEOUT_SECONDS = 300;
    private static final Object OAUTH_SESSION_LOCK = new Object();

    private static OAuthSession activeSession;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final CodexTokenStore tokenStore = new CodexTokenStore();

    public CodexAuthTokens ensureTokens(Path tokenFile) throws TranslationException {
        CodexAuthTokens saved = tokenStore.read(tokenFile);
        long now = Instant.now().getEpochSecond();
        if (saved != null && saved.isFresh(now)) {
            return saved;
        }

        if (saved != null && saved.refreshToken() != null && !saved.refreshToken().isBlank()) {
            try {
                CodexAuthTokens refreshed = refreshAccessToken(saved.refreshToken(), saved);
                tokenStore.write(tokenFile, refreshed);
                return refreshed;
            } catch (TranslationException e) {
                LOGGER.warn("Codex token refresh failed, fallback to browser OAuth. reason={}", e.getMessage());
            }
        }

        try {
            CodexAuthTokens fresh = runBrowserOAuth();
            tokenStore.write(tokenFile, fresh);
            return fresh;
        } catch (SupersededAuthorizationException e) {
            throw new TranslationException(e.getMessage(), e);
        }
    }

    public CodexAuthTokens authenticateInBrowser(Path tokenFile) throws TranslationException, SupersededAuthorizationException {
        CodexAuthTokens fresh = runBrowserOAuth();
        tokenStore.write(tokenFile, fresh);
        return fresh;
    }

    private CodexAuthTokens runBrowserOAuth() throws TranslationException, SupersededAuthorizationException {
        String verifier = generateCodeVerifier();
        String challenge = sha256Base64Url(verifier);
        String state = randomState();
        String redirectUri = "http://localhost:" + OAUTH_PORT + "/auth/callback";
        String authorizeUrl = buildAuthorizeUrl(redirectUri, challenge, state);
        OAuthSession session = replaceActiveSession();

        LOGGER.info("Starting Codex OAuth flow. URL={}", authorizeUrl);
        try (ServerSocket server = new ServerSocket()) {
            session.attach(server);
            throwIfSuperseded(session, null);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), OAUTH_PORT));
            openBrowser(authorizeUrl);
            OAuthCallback callback = waitForCallback(server, state, Duration.ofSeconds(OAUTH_TIMEOUT_SECONDS), session);
            if (callback.error() != null) {
                throw new TranslationException("OAuth failed: " + callback.error());
            }
            return exchangeCodeForTokens(callback.code(), redirectUri, verifier);
        } catch (BindException e) {
            throw new TranslationException("OAuth callback port " + OAUTH_PORT + " is unavailable.", e);
        } catch (SupersededAuthorizationException e) {
            throw e;
        } catch (IOException e) {
            throwIfSuperseded(session, e);
            throw new TranslationException("Failed to run OAuth callback server.", e);
        } finally {
            clearActiveSession(session);
        }
    }

    private CodexAuthTokens exchangeCodeForTokens(String code, String redirectUri, String verifier) throws TranslationException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", CLIENT_ID);
        form.put("code_verifier", verifier);
        JsonObject response = postForm(ISSUER + "/oauth/token", form);
        return toTokens(response, null);
    }

    private CodexAuthTokens refreshAccessToken(String refreshToken, CodexAuthTokens previous) throws TranslationException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", CLIENT_ID);
        JsonObject response = postForm(ISSUER + "/oauth/token", form);
        return toTokens(response, previous);
    }

    private JsonObject postForm(String url, Map<String, String> form) throws TranslationException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new TranslationException(
                    "OAuth token request failed (" + response.statusCode() + "): " + abbreviate(response.body(), 600)
                );
            }
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                throw new TranslationException("OAuth token endpoint returned non-object JSON.");
            }
            return parsed.getAsJsonObject();
        } catch (TranslationException e) {
            throw e;
        } catch (Exception e) {
            throw new TranslationException("OAuth token request failed.", e);
        }
    }

    private OAuthCallback waitForCallback(ServerSocket server, String expectedState, Duration timeout, OAuthSession session)
        throws TranslationException, SupersededAuthorizationException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            long remainingMillis = Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis();
            int socketTimeoutMs = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, remainingMillis));
            try {
                server.setSoTimeout(socketTimeoutMs);
                try (Socket socket = server.accept()) {
                    OAuthCallback callback = handleCallbackRequest(socket, expectedState);
                    if (callback != null) {
                        return callback;
                    }
                }
            } catch (SocketTimeoutException e) {
                break;
            } catch (SocketException e) {
                throwIfSuperseded(session, e);
                throw new TranslationException("Failed to receive OAuth callback.", e);
            } catch (IOException e) {
                throwIfSuperseded(session, e);
                throw new TranslationException("Failed to receive OAuth callback.", e);
            }
        }
        throwIfSuperseded(session, null);
        throw new TranslationException("OAuth callback timeout after " + timeout.toSeconds() + " seconds.");
    }

    private OAuthCallback handleCallbackRequest(Socket socket, String expectedState) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isBlank()) {
            writeHtmlResponse(socket, 400, "<html><body><h1>Authorization failed</h1></body></html>");
            return new OAuthCallback(null, "Malformed callback request");
        }
        while (true) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                break;
            }
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            writeHtmlResponse(socket, 400, "<html><body><h1>Authorization failed</h1></body></html>");
            return new OAuthCallback(null, "Malformed callback request");
        }

        URI uri;
        try {
            uri = URI.create("http://localhost" + parts[1]);
        } catch (IllegalArgumentException e) {
            writeHtmlResponse(socket, 400, "<html><body><h1>Authorization failed</h1><p>Invalid callback URI.</p></body></html>");
            return new OAuthCallback(null, "Invalid callback URI");
        }
        if (!"/auth/callback".equals(uri.getPath())) {
            writeHtmlResponse(socket, 404, "<html><body><h1>Not found</h1></body></html>");
            return null;
        }

        Map<String, String> params = parseQuery(uri.getRawQuery());
        String code = params.get("code");
        String state = params.get("state");
        String error = firstNonBlank(params.get("error_description"), params.get("error"));
        if (!error.isBlank()) {
            writeHtmlResponse(
                socket,
                400,
                "<html><body><h1>Authorization failed</h1><p>" + escapeHtml(error) + "</p></body></html>"
            );
            return new OAuthCallback(null, error);
        }
        if (code == null || code.isBlank()) {
            writeHtmlResponse(socket, 400, "<html><body><h1>Authorization failed</h1><p>Missing code.</p></body></html>");
            return new OAuthCallback(null, "Missing authorization code");
        }
        if (state == null || !state.equals(expectedState)) {
            writeHtmlResponse(socket, 400, "<html><body><h1>Authorization failed</h1><p>Invalid state.</p></body></html>");
            return new OAuthCallback(null, "Invalid state");
        }

        writeHtmlResponse(
            socket,
            200,
            "<html><body><h1>Authorization successful</h1><p>You can close this tab.</p></body></html>"
        );
        return new OAuthCallback(code, null);
    }

    private static void writeHtmlResponse(Socket socket, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + statusText(status) + "\r\n"
            + "Content-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            default -> "Response";
        };
    }

    private static CodexAuthTokens toTokens(JsonObject payload, CodexAuthTokens previous) {
        String accessToken = readString(payload, "access_token");
        String refreshToken = firstNonBlank(readString(payload, "refresh_token"), previous == null ? null : previous.refreshToken());
        String idToken = firstNonBlank(readString(payload, "id_token"), previous == null ? null : previous.idToken());
        long expiresIn = readLong(payload, "expires_in", 3600L);
        long expiresAt = Instant.now().getEpochSecond() + expiresIn;
        String accountId = extractAccountId(idToken, accessToken);
        if (accountId.isBlank() && previous != null) {
            accountId = previous.accountId();
        }
        return new CodexAuthTokens(accessToken, refreshToken, idToken, accountId, expiresAt);
    }

    private static String extractAccountId(String idToken, String accessToken) {
        String fromId = pickAccountId(parseJwtClaims(idToken));
        if (!fromId.isBlank()) {
            return fromId;
        }
        return pickAccountId(parseJwtClaims(accessToken));
    }

    private static String pickAccountId(JsonObject claims) {
        if (claims == null) {
            return "";
        }
        String direct = readString(claims, "chatgpt_account_id");
        if (!direct.isBlank()) {
            return direct;
        }
        if (claims.has("https://api.openai.com/auth") && claims.get("https://api.openai.com/auth").isJsonObject()) {
            String nested = readString(claims.getAsJsonObject("https://api.openai.com/auth"), "chatgpt_account_id");
            if (!nested.isBlank()) {
                return nested;
            }
        }
        if (claims.has("organizations") && claims.get("organizations").isJsonArray() && !claims.getAsJsonArray("organizations").isEmpty()) {
            JsonElement first = claims.getAsJsonArray("organizations").get(0);
            if (first.isJsonObject()) {
                return readString(first.getAsJsonObject(), "id");
            }
        }
        return "";
    }

    private static JsonObject parseJwtClaims(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String payload = parts[1];
        int padding = (4 - payload.length() % 4) % 4;
        payload = payload + "=".repeat(padding);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            JsonElement parsed = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String buildAuthorizeUrl(String redirectUri, String challenge, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", CLIENT_ID);
        params.put("redirect_uri", redirectUri);
        params.put("scope", OAUTH_SCOPE);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("id_token_add_organizations", "true");
        params.put("codex_cli_simplified_flow", "true");
        params.put("state", state);
        params.put("originator", "opencode");
        return ISSUER + "/oauth/authorize?" + encodeForm(params);
    }

    private static void openBrowser(String url) throws TranslationException {
        Exception desktopFailure = null;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            desktopFailure = e;
        }

        try {
            Util.getPlatform().openUri(url);
            return;
        } catch (Exception e) {
            if (desktopFailure != null) {
                e.addSuppressed(desktopFailure);
            }
            throw new TranslationException("Failed to open browser for OAuth. URL: " + url, e);
        }
    }

    private static OAuthSession replaceActiveSession() {
        synchronized (OAUTH_SESSION_LOCK) {
            if (activeSession != null) {
                activeSession.cancel();
            }
            activeSession = new OAuthSession();
            return activeSession;
        }
    }

    private static void clearActiveSession(OAuthSession session) {
        synchronized (OAUTH_SESSION_LOCK) {
            if (activeSession == session) {
                activeSession = null;
            }
        }
    }

    private static void throwIfSuperseded(OAuthSession session, Exception cause) throws SupersededAuthorizationException {
        if (session != null && session.isCancelled()) {
            throw new SupersededAuthorizationException(cause);
        }
    }

    private static String generateCodeVerifier() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        StringBuilder verifier = new StringBuilder(43);
        for (int i = 0; i < 43; i++) {
            verifier.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return verifier.toString();
    }

    private static String sha256Base64Url(String value) throws TranslationException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new TranslationException("Failed to generate PKCE challenge.", e);
        }
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            String[] keyValue = pair.split("=", 2);
            String key = urlDecode(keyValue[0]);
            String value = keyValue.length > 1 ? urlDecode(keyValue[1]) : "";
            query.put(key, value);
        }
        return query;
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(urlEncode(entry.getKey()));
            encoded.append('=');
            encoded.append(urlEncode(entry.getValue()));
        }
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    private static long readLong(JsonObject json, String key, long fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private static final class OAuthSession {
        private volatile boolean cancelled;
        private volatile ServerSocket server;

        void attach(ServerSocket server) {
            this.server = server;
            if (cancelled) {
                closeServer(server);
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
            closeServer(server);
        }

        private static void closeServer(ServerSocket server) {
            if (server == null) {
                return;
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // Best effort only.
            }
        }
    }

    public static final class SupersededAuthorizationException extends Exception {
        public SupersededAuthorizationException(Throwable cause) {
            super("OAuth flow was cancelled because a new authorization started.", cause);
        }
    }

    private record OAuthCallback(String code, String error) {
    }
}
