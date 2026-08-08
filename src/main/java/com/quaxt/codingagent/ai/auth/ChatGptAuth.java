package com.quaxt.codingagent.ai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.ai.http.HttpException;
import com.quaxt.codingagent.ai.http.HttpTransport;
import com.quaxt.codingagent.ai.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** OpenAI Codex device authorization for ChatGPT subscription access. */
public final class ChatGptAuth {
	public static final String PROVIDER_ID = "chatgpt";
	public static final URI CODEX_API_BASE_URL = URI.create("https://chatgpt.com/backend-api/codex");
	private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
	private static final String ACCOUNT_ID = "accountId";
	private static final long REFRESH_SKEW_MS = 5 * 60 * 1000L;
	private static final long DEVICE_CODE_LIFETIME_MS = 15 * 60 * 1000L;

	private final CredentialStore credentials;
	private final URI authBaseUrl;
	private final String clientId;

	public ChatGptAuth(CredentialStore credentials) {
		this(credentials, URI.create("https://auth.openai.com"), CLIENT_ID);
	}

	/** Visible for deterministic HTTP tests. */
	public ChatGptAuth(CredentialStore credentials, URI authBaseUrl, String clientId) {
		this.credentials = credentials;
		this.authBaseUrl = requireAbsoluteHttpUri(authBaseUrl, "authBaseUrl");
		if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");
		this.clientId = clientId;
	}

	/** Starts the Codex device flow. Display the URI and code before completing it. */
	public DeviceCode beginLogin() throws IOException {
		ObjectNode request = Json.object().put("client_id", clientId);
		JsonNode response = postJson(authBaseUrl.resolve("/api/accounts/deviceauth/usercode"), request);
		String deviceAuthId = requiredText(response, "device_auth_id");
		String userCode = requiredText(response, "user_code");
		int interval = parseInterval(response.path("interval"));
		return new DeviceCode(
				deviceAuthId,
				userCode,
				authBaseUrl.resolve("/codex/device"),
				interval,
				System.currentTimeMillis() + DEVICE_CODE_LIFETIME_MS);
	}

	/** Waits for browser authorization, exchanges the code, and saves refreshable tokens. */
	public Credential.OAuthCredential completeLogin(DeviceCode device) throws IOException, InterruptedException {
		JsonNode authorization = pollForAuthorization(device);
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", requiredText(authorization, "authorization_code"));
		form.put("redirect_uri", "https://auth.openai.com/deviceauth/callback");
		form.put("client_id", clientId);
		form.put("code_verifier", requiredText(authorization, "code_verifier"));
		Credential.OAuthCredential credential = credentialFromTokenResponse(postForm(authBaseUrl.resolve("/oauth/token"), form), null);
		credentials.modify(PROVIDER_ID, ignored -> credential);
		return credential;
	}

	/** Returns a usable ChatGPT bearer token, refreshing it when close to expiry. */
	public ChatGptToken resolveToken() throws IOException {
		Credential.OAuthCredential oauth = oauthCredential();
		if (oauth.expires() > System.currentTimeMillis() && !oauth.access().isBlank()) {
			return token(oauth);
		}
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", oauth.refresh());
		form.put("client_id", clientId);
		Credential.OAuthCredential refreshed = credentialFromTokenResponse(postForm(authBaseUrl.resolve("/oauth/token"), form), oauth);
		credentials.modify(PROVIDER_ID, ignored -> refreshed);
		return token(refreshed);
	}

	public boolean hasCredential() throws IOException {
		Optional<Credential> credential = credentials.read(PROVIDER_ID);
		return credential.filter(Credential.OAuthCredential.class::isInstance)
				.map(Credential.OAuthCredential.class::cast)
				.map(value -> !value.refresh().isBlank())
				.orElse(false);
	}

	public void logout() throws IOException {
		credentials.delete(PROVIDER_ID);
	}

	private JsonNode pollForAuthorization(DeviceCode device) throws IOException, InterruptedException {
		ObjectNode request = Json.object()
				.put("device_auth_id", device.deviceAuthId())
				.put("user_code", device.userCode());
		while (System.currentTimeMillis() < device.expiresAtMs()) {
			try {
				return postJson(authBaseUrl.resolve("/api/accounts/deviceauth/token"), request);
			} catch (HttpException error) {
				if (error.status() != 403 && error.status() != 404) throw error;
				sleep(device.intervalSeconds());
			}
		}
		throw new IOException("ChatGPT device authorization expired before completion");
	}

	private Credential.OAuthCredential credentialFromTokenResponse(JsonNode response, Credential.OAuthCredential previous)
			throws IOException {
		String access = requiredText(response, "access_token");
		String refresh = response.path("refresh_token").asText(previous == null ? "" : previous.refresh());
		if (refresh.isBlank()) throw new IOException("Invalid OpenAI response: missing refresh_token");
		long expiresIn = response.path("expires_in").asLong(3600);
		long expires = System.currentTimeMillis() + Math.max(1, expiresIn) * 1000 - REFRESH_SKEW_MS;
		Map<String, String> metadata = new LinkedHashMap<>(previous == null ? Map.of() : previous.metadata());
		String idToken = response.path("id_token").asText();
		String accountId = accountIdFromJwt(idToken);
		if (!accountId.isBlank()) metadata.put(ACCOUNT_ID, accountId);
		if (!metadata.containsKey(ACCOUNT_ID)) throw new IOException("OpenAI login did not return a ChatGPT account id");
		return new Credential.OAuthCredential(access, refresh, expires, null, metadata);
	}

	private Credential.OAuthCredential oauthCredential() throws IOException {
		Credential credential = credentials.read(PROVIDER_ID)
				.orElseThrow(() -> new IOException("ChatGPT Plus/Pro is not logged in. Run /login."));
		if (credential instanceof Credential.OAuthCredential oauth) return oauth;
		throw new IOException("ChatGPT credential is not an OAuth credential. Run /login.");
	}

	private static ChatGptToken token(Credential.OAuthCredential credential) throws IOException {
		String accountId = credential.metadata().get(ACCOUNT_ID);
		if (accountId == null || accountId.isBlank()) throw new IOException("Saved ChatGPT login is missing its account id. Run /login again.");
		return new ChatGptToken(credential.access(), accountId);
	}

	private static String accountIdFromJwt(String token) throws IOException {
		if (token == null || token.isBlank()) return "";
		String[] parts = token.split("\\.");
		if (parts.length < 2) throw new IOException("OpenAI returned an invalid ID token");
		try {
			JsonNode claims = Json.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
			return claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asText(
					claims.path("https://api.openai.com/auth.chatgpt_account_id").asText());
		} catch (IllegalArgumentException error) {
			throw new IOException("OpenAI returned an invalid ID token", error);
		}
	}

	private static int parseInterval(JsonNode node) throws IOException {
		int interval;
		try {
			interval = node.isIntegralNumber() ? node.asInt() : Integer.parseInt(node.asText("5"));
		} catch (NumberFormatException error) {
			throw new IOException("Invalid OpenAI device response: invalid interval", error);
		}
		if (interval < 0) throw new IOException("Invalid OpenAI device response: interval must not be negative");
		return interval;
	}

	private static JsonNode postJson(URI url, JsonNode body) throws IOException {
		try (HttpTransport.Response response = HttpTransport.postJson(
				url.toString(), Map.of("Accept", "application/json"), Json.MAPPER.writeValueAsBytes(body), null, null)) {
			return Json.MAPPER.readTree(response.body());
		}
	}

	private static JsonNode postForm(URI url, Map<String, String> parameters) throws IOException {
		String body = parameters.entrySet().stream()
				.map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
						+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.reduce((left, right) -> left + "&" + right)
				.orElse("");
		try (HttpTransport.Response response = HttpTransport.postForm(
				url.toString(), Map.of("Accept", "application/json"), body.getBytes(StandardCharsets.UTF_8), null, null)) {
			return Json.MAPPER.readTree(response.body());
		}
	}

	private static String requiredText(JsonNode node, String field) throws IOException {
		if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
			throw new IOException("Invalid OpenAI response: missing " + field);
		}
		return node.path(field).asText();
	}

	private static URI requireAbsoluteHttpUri(URI uri, String field) {
		if (uri == null || !uri.isAbsolute() || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
			throw new IllegalArgumentException(field + " must be an absolute HTTP(S) URI");
		}
		return uri;
	}

	private static void sleep(int seconds) throws InterruptedException {
		if (seconds > 0) Thread.sleep(seconds * 1000L);
	}

	public record DeviceCode(String deviceAuthId, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {}

	public record ChatGptToken(String accessToken, String accountId) {}
}
