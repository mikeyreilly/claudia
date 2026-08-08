package com.quaxt.codingagent.ai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quaxt.codingagent.ai.http.HttpTransport;
import com.quaxt.codingagent.ai.http.HttpException;
import com.quaxt.codingagent.ai.json.Json;

/** github.com device authorization and Copilot API-token lifecycle. */
public final class GitHubCopilotAuth {
	public static final String PROVIDER_ID = "github-copilot";
	private static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
	private static final String USER_AGENT = "GitHubCopilotChat/0.35.0";
	private static final String DEFAULT_COPILOT_BASE_URL = "https://api.individual.githubcopilot.com";
	private static final Pattern PROXY_ENDPOINT = Pattern.compile("(?:^|;)proxy-ep=([^;]+)");
	private static final long REFRESH_SKEW_MS = 5 * 60 * 1000L;

	private final CredentialStore credentials;
	private final URI githubBaseUrl;
	private final URI copilotTokenUrl;
	private final URI defaultCopilotBaseUrl;

	public GitHubCopilotAuth(CredentialStore credentials) {
		this(
				credentials,
				URI.create("https://github.com"),
				URI.create("https://api.github.com/copilot_internal/v2/token"),
				URI.create(DEFAULT_COPILOT_BASE_URL));
	}

	/** Visible for deterministic HTTP tests. Production callers use {@link #GitHubCopilotAuth(CredentialStore)}. */
	public GitHubCopilotAuth(
			CredentialStore credentials, URI githubBaseUrl, URI copilotTokenUrl, URI defaultCopilotBaseUrl) {
		this.credentials = credentials;
		this.githubBaseUrl = requireAbsoluteHttpUri(githubBaseUrl, "githubBaseUrl");
		this.copilotTokenUrl = requireAbsoluteHttpUri(copilotTokenUrl, "copilotTokenUrl");
		this.defaultCopilotBaseUrl = requireAbsoluteHttpUri(defaultCopilotBaseUrl, "defaultCopilotBaseUrl");
	}

	/** Starts the device flow. Display the resulting URI and code before calling {@link #completeLogin(DeviceCode)}. */
	public DeviceCode beginLogin() throws IOException {
		JsonNode response = postForm(
				githubBaseUrl.resolve("/login/device/code"),
				Map.of("client_id", CLIENT_ID, "scope", "read:user"));
		String deviceCode = requiredText(response, "device_code");
		String userCode = requiredText(response, "user_code");
		URI verificationUri = requireAbsoluteHttpUri(URI.create(requiredText(response, "verification_uri")), "verification_uri");
		long expiresIn = requiredPositiveLong(response, "expires_in");
		int interval = response.path("interval").isIntegralNumber() ? response.path("interval").asInt() : 5;
		if (interval < 0) {
			throw new IOException("Invalid device code response: interval must not be negative");
		}
		return new DeviceCode(deviceCode, userCode, verificationUri, interval, System.currentTimeMillis() + expiresIn * 1000);
	}

	/** Polls GitHub, exchanges the durable GitHub token for a Copilot token, and saves the credential. */
	public Credential.OAuthCredential completeLogin(DeviceCode device) throws IOException, InterruptedException {
		String githubAccessToken = pollForGitHubAccessToken(device);
		Credential.OAuthCredential credential = createCredential(githubAccessToken, null);
		try {
			credential = withAvailableModels(credential);
		} catch (IOException ignored) {
			// The Copilot token is valid even if its optional model catalog is transiently unavailable.
		}
		Credential.OAuthCredential saved = credential;
		credentials.modify(PROVIDER_ID, ignored -> saved);
		return credential;
	}

	/** Returns a valid Copilot API token, refreshing it from the stored GitHub token when necessary. */
	public CopilotToken resolveToken() throws IOException {
		Credential credential = credentials.read(PROVIDER_ID)
				.orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
		if (!(credential instanceof Credential.OAuthCredential oauth)) {
			throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
		}
		if (!oauth.isExpired(System.currentTimeMillis()) && !oauth.access().isBlank()) {
			return token(oauth);
		}
		Credential.OAuthCredential refreshed = createCredential(oauth.refresh(), oauth.availableModelIds());
		try {
			refreshed = withAvailableModels(refreshed);
		} catch (IOException ignored) {
			// Retain the last known entitlement list if model discovery cannot be refreshed.
		}
		Credential.OAuthCredential saved = refreshed;
		credentials.modify(PROVIDER_ID, ignored -> saved);
		return token(refreshed);
	}

	/** Reports whether a saved GitHub OAuth credential can be refreshed. */
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

	/** Enables the listed Copilot model policies and reports how many policy requests GitHub accepted. */
	public int enableModels(List<String> modelIds) throws IOException {
		CopilotToken token = resolveToken();
		int enabled = 0;
		for (String modelId : modelIds) {
			URI policyUrl = token.baseUrl().resolve("/models/" + encodePath(modelId) + "/policy");
			Map<String, String> headers = modelHeaders(token.accessToken());
			headers.put("openai-intent", "chat-policy");
			headers.put("x-interaction-type", "chat-policy");
			try (HttpTransport.Response ignored = HttpTransport.postJson(
					policyUrl.toString(),
					headers,
					"{\"state\":\"enabled\"}".getBytes(StandardCharsets.UTF_8),
					5_000,
					null)) {
				enabled++;
			} catch (HttpException ignored) {
				// Some account plans do not expose every catalog model; continue enabling the rest.
			}
		}
		return enabled;
	}

	/** Re-fetches and persists the enabled-model list without minting a new Copilot API token. */
	public CopilotToken refreshAvailableModels() throws IOException {
		CopilotToken current = resolveToken();
		Credential.OAuthCredential oauth = oauthCredential();
		Credential.OAuthCredential refreshed =
				new Credential.OAuthCredential(oauth.access(), oauth.refresh(), oauth.expires(), fetchAvailableModelIds(current.accessToken()));
		credentials.modify(PROVIDER_ID, ignored -> refreshed);
		return token(refreshed);
	}

	private Credential.OAuthCredential createCredential(String githubAccessToken, List<String> availableModelIds)
			throws IOException {
		if (githubAccessToken == null || githubAccessToken.isBlank()) {
			throw new IOException("GitHub returned an empty access token");
		}
		JsonNode response;
		try (HttpTransport.Response http = HttpTransport.get(
				copilotTokenUrl.toString(),
				copilotHeaders("token " + githubAccessToken),
				null,
				null)) {
			response = Json.MAPPER.readTree(http.body());
		}
		String token = requiredText(response, "token");
		long expiresAtSeconds = requiredPositiveLong(response, "expires_at");
		long expires = Math.max(System.currentTimeMillis(), expiresAtSeconds * 1000 - REFRESH_SKEW_MS);
		return new Credential.OAuthCredential(token, githubAccessToken, expires, availableModelIds);
	}

	private Credential.OAuthCredential withAvailableModels(Credential.OAuthCredential credential) throws IOException {
		List<String> available = fetchAvailableModelIds(credential.access());
		return new Credential.OAuthCredential(credential.access(), credential.refresh(), credential.expires(), available);
	}

	private List<String> fetchAvailableModelIds(String copilotToken) throws IOException {
		URI modelsUrl = baseUrlFromToken(copilotToken).resolve("/models");
		JsonNode response;
		try (HttpTransport.Response http =
				HttpTransport.get(modelsUrl.toString(), modelHeaders(copilotToken), 5_000, null)) {
			response = Json.MAPPER.readTree(http.body());
		}
		JsonNode data = response.path("data");
		if (!data.isArray()) {
			throw new IOException("Invalid Copilot models response");
		}
		List<String> pickerEnabled = new ArrayList<>();
		List<String> policyEnabled = new ArrayList<>();
		for (JsonNode model : data) {
			if (!model.path("id").isTextual() || model.path("capabilities").path("supports").path("tool_calls").asBoolean(true) == false) {
				continue;
			}
			String id = model.path("id").asText();
			if (model.path("model_picker_enabled").asBoolean(false)
					&& !model.path("policy").path("state").asText().equals("disabled")) {
				pickerEnabled.add(id);
			}
			if (model.path("policy").path("state").asText().equals("enabled")) {
				policyEnabled.add(id);
			}
		}
		return pickerEnabled.isEmpty() && baseUrlFromToken(copilotToken).toString().equals(DEFAULT_COPILOT_BASE_URL)
				? List.copyOf(policyEnabled)
				: List.copyOf(pickerEnabled);
	}

	private String pollForGitHubAccessToken(DeviceCode device) throws IOException, InterruptedException {
		int intervalSeconds = device.intervalSeconds();
		while (System.currentTimeMillis() < device.expiresAtMs()) {
			JsonNode response = postForm(
					githubBaseUrl.resolve("/login/oauth/access_token"),
					Map.of(
							"client_id", CLIENT_ID,
							"device_code", device.deviceCode(),
							"grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
			if (response.path("access_token").isTextual()) {
				return response.path("access_token").asText();
			}
			String error = response.path("error").asText();
			if (error.equals("authorization_pending")) {
				sleep(intervalSeconds);
				continue;
			}
			if (error.equals("slow_down")) {
				intervalSeconds = Math.max(intervalSeconds + 5, response.path("interval").asInt(0));
				sleep(intervalSeconds);
				continue;
			}
			String description = response.path("error_description").asText();
			throw new IOException("GitHub device authorization failed: " + error + (description.isBlank() ? "" : ": " + description));
		}
		throw new IOException("GitHub device authorization expired before completion");
	}

	private CopilotToken token(Credential.OAuthCredential credential) {
		return new CopilotToken(credential.access(), baseUrlFromToken(credential.access()), credential.availableModelIds());
	}

	private Credential.OAuthCredential oauthCredential() throws IOException {
		Credential credential = credentials.read(PROVIDER_ID)
				.orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
		if (credential instanceof Credential.OAuthCredential oauth) {
			return oauth;
		}
		throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
	}

	private URI baseUrlFromToken(String token) {
		Matcher match = PROXY_ENDPOINT.matcher(token);
		if (!match.find()) {
			return defaultCopilotBaseUrl;
		}
		String host = match.group(1);
		if (!host.matches("[A-Za-z0-9.-]+")) {
			return defaultCopilotBaseUrl;
		}
		return URI.create("https://" + host.replaceFirst("^proxy\\.", "api."));
	}

	private static Map<String, String> copilotHeaders(String authorization) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Accept", "application/json");
		headers.put("Authorization", authorization);
		headers.put("User-Agent", USER_AGENT);
		headers.put("Editor-Version", "vscode/1.107.0");
		headers.put("Editor-Plugin-Version", "copilot-chat/0.35.0");
		headers.put("Copilot-Integration-Id", "vscode-chat");
		return headers;
	}

	private static Map<String, String> modelHeaders(String copilotToken) {
		Map<String, String> headers = copilotHeaders("Bearer " + copilotToken);
		headers.put("X-GitHub-Api-Version", "2026-06-01");
		return headers;
	}

	private static String encodePath(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static JsonNode postForm(URI url, Map<String, String> parameters) throws IOException {
		String body = parameters.entrySet().stream()
				.map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
						+ "="
						+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.reduce((left, right) -> left + "&" + right)
				.orElse("");
		try (HttpTransport.Response response = HttpTransport.postForm(
				url.toString(),
				Map.of("Accept", "application/json", "User-Agent", USER_AGENT),
				body.getBytes(StandardCharsets.UTF_8),
				null,
				null)) {
			return Json.MAPPER.readTree(response.body());
		}
	}

	private static String requiredText(JsonNode node, String field) throws IOException {
		if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
			throw new IOException("Invalid GitHub response: missing " + field);
		}
		return node.path(field).asText();
	}

	private static long requiredPositiveLong(JsonNode node, String field) throws IOException {
		if (!node.path(field).isIntegralNumber() || node.path(field).asLong() <= 0) {
			throw new IOException("Invalid GitHub response: missing " + field);
		}
		return node.path(field).asLong();
	}

	private static URI requireAbsoluteHttpUri(URI uri, String field) {
		if (uri == null || !uri.isAbsolute() || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
			throw new IllegalArgumentException(field + " must be an absolute HTTP(S) URI");
		}
		return uri;
	}

	private static void sleep(int seconds) throws InterruptedException {
		if (seconds > 0) {
			Thread.sleep(seconds * 1000L);
		}
	}

	public record DeviceCode(String deviceCode, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {}

	/** A current derived Copilot bearer token plus the credential-specific API endpoint. */
	public record CopilotToken(String accessToken, URI baseUrl, List<String> availableModelIds) {
		public CopilotToken {
			availableModelIds = availableModelIds == null ? null : List.copyOf(availableModelIds);
		}
	}
}
