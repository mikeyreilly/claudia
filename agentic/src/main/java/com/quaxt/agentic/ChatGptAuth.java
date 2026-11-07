package com.quaxt.agentic;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Codex device authorization and refresh, extracted from Claudia's runtime.
 * The host supplies HTTP, JSON and credential storage; no application paths are assumed.
 */
public final class ChatGptAuth {
    public static final String DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final URI AUTH_BASE = URI.create("https://auth.openai.com");
    public static final URI CODEX_BASE = URI.create("https://chatgpt.com/backend-api/codex");
    public interface Codec {
        String encode(Object value) throws IOException;
        Map<String, Object> decode(String json) throws IOException;
    }
    public interface Transport {
        Map<String, Object> post(URI uri, Map<String, Object> body, boolean form) throws IOException;
    }
    public interface Store {
        OAuth read() throws IOException;
        void write(OAuth value) throws IOException;
        void clear() throws IOException;
        default Object lock() { return this; }
    }
    public static final class HttpError extends IOException {
        public final int status;
        public HttpError(int status) { super("Authorization request failed (HTTP " + status + ")"); this.status = status; }
    }
    public record Device(String deviceAuthId, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {
        @Override public String toString() { return "Device[authorization pending]"; }
    }
    public record OAuth(String access, String refresh, long expires, String accountId) {
        @Override public String toString() { return "OAuth[redacted]"; }
    }
    private final URI base;
    private final String clientId;
    private final Transport http;
    private final Store store;
    private final Codec codec;

    public ChatGptAuth(URI base, String clientId, Transport http, Store store, Codec codec) {
        if (base == null || !base.isAbsolute() || !("https".equals(base.getScheme()) || "http".equals(base.getScheme())))
            throw new IllegalArgumentException("authBaseUrl must be an absolute HTTP(S) URI");
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");
        this.base = base; this.clientId = clientId; this.http = http; this.store = store; this.codec = codec;
    }
    public Device begin() throws IOException {
        Map<String, Object> response = http.post(base.resolve("/api/accounts/deviceauth/usercode"), Map.of("client_id", clientId), false);
        int interval;
        try { interval = Integer.parseInt(String.valueOf(response.getOrDefault("interval", "5"))); }
        catch (NumberFormatException error) { throw new IOException("Invalid OpenAI device response: invalid interval"); }
        if (interval < 0) throw new IOException("Invalid OpenAI device response: interval must not be negative");
        return new Device(required(response, "device_auth_id"), required(response, "user_code"),
                base.resolve("/codex/device"), interval, System.currentTimeMillis() + 15 * 60 * 1000L);
    }
    /** One poll, suitable for a web host. Empty means browser approval is still pending. */
    public Optional<OAuth> poll(Device device) throws IOException {
        if (System.currentTimeMillis() >= device.expiresAtMs) throw new IOException("ChatGPT device authorization expired before completion");
        Map<String, Object> authorization;
        try {
            authorization = http.post(base.resolve("/api/accounts/deviceauth/token"),
                    Map.of("device_auth_id", device.deviceAuthId, "user_code", device.userCode), false);
        } catch (HttpError error) {
            if (error.status == 403 || error.status == 404) return Optional.empty();
            throw error;
        }
        OAuth credential = fromResponse(http.post(base.resolve("/oauth/token"),
                Map.of("grant_type", "authorization_code", "code", required(authorization, "authorization_code"),
                       "redirect_uri", "https://auth.openai.com/deviceauth/callback", "client_id", clientId,
                       "code_verifier", required(authorization, "code_verifier")), true), null);
        synchronized (store.lock()) { store.write(credential); }
        return Optional.of(credential);
    }
    public OAuth complete(Device device) throws IOException, InterruptedException {
        while (System.currentTimeMillis() < device.expiresAtMs) {
            Optional<OAuth> result = poll(device);
            if (result.isPresent()) return result.get();
            if (device.intervalSeconds > 0) Thread.sleep(device.intervalSeconds * 1000L);
        }
        throw new IOException("ChatGPT device authorization expired before completion");
    }
    public OAuth resolve() throws IOException {
        synchronized (store.lock()) {
            OAuth credential = store.read();
            if (credential == null || credential.refresh == null || credential.refresh.isBlank()) throw new IOException("ChatGPT is not signed in");
            if (credential.accountId == null || credential.accountId.isBlank()) throw new IOException("Saved ChatGPT login is missing its account id");
            if (credential.expires > System.currentTimeMillis() && !credential.access.isBlank()) return credential;
            OAuth refreshed = fromResponse(http.post(base.resolve("/oauth/token"),
                    Map.of("grant_type", "refresh_token", "refresh_token", credential.refresh, "client_id", clientId), true), credential);
            store.write(refreshed);
            return refreshed;
        }
    }
    public boolean signedIn() throws IOException {
        synchronized (store.lock()) { OAuth value = store.read(); return value != null && value.refresh != null && !value.refresh.isBlank(); }
    }
    public void logout() throws IOException { synchronized (store.lock()) { store.clear(); } }
    private OAuth fromResponse(Map<String, Object> response, OAuth previous) throws IOException {
        String access = required(response, "access_token");
        String refresh = String.valueOf(response.getOrDefault("refresh_token", previous == null ? "" : previous.refresh));
        if (refresh.isBlank()) throw new IOException("Invalid OpenAI response: missing refresh_token");
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        long expires = System.currentTimeMillis() + Math.max(1, expiresIn) * 1000 - 5 * 60 * 1000L;
        String account = previous == null ? "" : previous.accountId;
        Object idToken = response.get("id_token");
        if (idToken instanceof String token && !token.isBlank()) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length < 2) throw new IllegalArgumentException();
                Map<String, Object> claims = codec.decode(new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8));
                Object auth = claims.get("https://api.openai.com/auth");
                Object value = auth instanceof Map<?, ?> map ? map.get("chatgpt_account_id") : claims.get("https://api.openai.com/auth.chatgpt_account_id");
                if (value instanceof String text && !text.isBlank()) account = text;
            } catch (IllegalArgumentException error) { throw new IOException("OpenAI returned an invalid ID token"); }
        }
        if (account == null || account.isBlank()) throw new IOException("OpenAI login did not return a ChatGPT account id");
        return new OAuth(access, refresh, expires, account);
    }
    private static String required(Map<String, Object> value, String key) throws IOException {
        Object text = value.get(key);
        if (!(text instanceof String result) || result.isBlank()) throw new IOException("Invalid OpenAI response: missing " + key);
        return result;
    }
}
