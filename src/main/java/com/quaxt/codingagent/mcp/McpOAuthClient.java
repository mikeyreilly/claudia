package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quaxt.codingagent.ai.json.Json;

/** OAuth 2.1 authorization-code, PKCE, dynamic-registration, and refresh support for remote MCP. */
final class McpOAuthClient {
	private static final int DEFAULT_CALLBACK_PORT = 19_876;
	private static final String DEFAULT_CALLBACK_PATH = "/mcp/oauth/callback";
	private static final Duration DEFAULT_CALLBACK_TIMEOUT = Duration.ofMinutes(5);
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
	private static final long REFRESH_SKEW_SECONDS = 30;
	private static final String PROTOCOL_VERSION = "2025-11-25";
	private static final Pattern AUTH_PARAMETER = Pattern.compile(
			"(?i)(?:^|[,\\s])([a-z][a-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

	@FunctionalInterface
	interface Browser {
		boolean open(URI uri);
	}

	private final McpOAuthStore store;
	private final HttpClient http;
	private final Browser browser;
	private final Duration callbackTimeout;
	private final SecureRandom random = new SecureRandom();
	private final ReentrantLock interactiveLock = new ReentrantLock();

	McpOAuthClient(McpOAuthStore store, HttpClient http, Browser browser, Duration callbackTimeout) {
		this.store = Objects.requireNonNull(store, "store");
		this.http = Objects.requireNonNull(http, "http");
		this.browser = Objects.requireNonNull(browser, "browser");
		this.callbackTimeout = Objects.requireNonNull(callbackTimeout, "callbackTimeout");
	}

	static McpOAuthClient defaultClient() {
		HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(15))
				.build();
		return new McpOAuthClient(McpOAuthStore.defaultStore(), http, McpBrowser::open, DEFAULT_CALLBACK_TIMEOUT);
	}

	Session session(String name, McpServerConfig.Remote config) {
		return OAuthSettings.disabled(config.oauth()) || hasAuthorization(config.headers())
				? null
				: new Session(name, config);
	}

	final class Session {
		private final String name;
		private final McpServerConfig.Remote config;
		private final OAuthSettings settings;
		private McpOAuthStore.Entry entry;
		private boolean loaded;

		private Session(String name, McpServerConfig.Remote config) {
			this.name = name;
			this.config = config;
			this.settings = OAuthSettings.parse(config.oauth());
		}

		/** Returns a current token and silently refreshes it when it is near expiry. */
		synchronized String accessToken() throws Exception {
			McpOAuthStore.Entry current = load();
			if (current == null || current.tokens() == null) return null;
			McpOAuthStore.Tokens tokens = current.tokens();
			if (!expired(tokens)) return tokens.accessToken();
			if (tokens.refreshToken() == null) {
				return tokens.expiresAt() != null && tokens.expiresAt() > Instant.now().getEpochSecond()
						? tokens.accessToken()
						: null;
			}
			try {
				return refresh(current, null).tokens().accessToken();
			} catch (OAuthFailure error) {
				if (invalidCredential(error)) {
					invalidateAfterRefreshFailure(current, error);
					return null;
				}
				throw error;
			} catch (IOException error) {
				// A refresh attempted inside the safety window must not waste an access token that is still valid.
				if (tokens.expiresAt() != null && tokens.expiresAt() > Instant.now().getEpochSecond()) {
					return tokens.accessToken();
				}
				throw error;
			}
		}

		/** Returns the last loaded token without doing I/O; used only for best-effort session cleanup. */
		synchronized String cachedAccessToken() {
			return entry == null || entry.tokens() == null ? null : entry.tokens().accessToken();
		}

		/** Refreshes a token rejected by the resource server and tells the transport whether to retry. */
		synchronized boolean refreshAfterUnauthorized(McpHttpException failure, String rejectedToken) throws Exception {
			Challenge challenge = Challenge.from(failure);
			if (challenge == null || challenge.insufficientScope() || rejectedToken == null) return false;
			McpOAuthStore.Entry current = reload();
			if (current == null || current.tokens() == null) return false;
			if (!current.tokens().accessToken().equals(rejectedToken)) return true;
			if (current.tokens().refreshToken() == null) return false;
			try {
				McpOAuthStore.Entry refreshed = refresh(current, challenge);
				return !refreshed.tokens().accessToken().equals(rejectedToken);
			} catch (OAuthFailure error) {
				if (invalidCredential(error)) {
					invalidateAfterRefreshFailure(current, error);
					return false;
				}
				throw error;
			}
		}

		/** Runs an interactive authorization flow and leaves the new token available to this transport. */
		void authorize(McpHttpException failure, Consumer<URI> listener) throws Exception {
			Challenge challenge = Challenge.from(failure);
			if (challenge == null) throw failure;
			interactiveLock.lockInterruptibly();
			try {
				Discovery discovery = discover(config, challenge);
				String state = randomUrlToken(32);
				String verifier = randomUrlToken(64);
				String challengeValue = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
				URI redirectUri = settings.redirectUri();
				try (McpOAuthCallback callback = new McpOAuthCallback(redirectUri, state)) {
					McpOAuthStore.Entry current = reload();
					String scope = selectScope(challenge, discovery.resourceMetadata(), settings.scope());
					McpOAuthStore.ClientInfo client = clientInformation(current, discovery, redirectUri, scope);
					URI authorizationUrl = authorizationUrl(
							discovery, client, redirectUri, state, challengeValue, scope);
					if (listener != null) listener.accept(authorizationUrl);
					browser.open(authorizationUrl);
					String code = callback.await(callbackTimeout);
					McpOAuthStore.Tokens tokens = exchangeCode(
							discovery, client, code, verifier, redirectUri, Map.of());
					if (tokens.scope() == null && scope != null) {
						tokens = new McpOAuthStore.Tokens(
								tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt(), scope);
					}
					McpOAuthStore.Entry saved = new McpOAuthStore.Entry(
							tokens, settings.clientId() == null ? client : null);
					store.write(name, config.url().toString(), saved);
					entry = saved;
					loaded = true;
				}
			} finally {
				interactiveLock.unlock();
			}
		}

		private McpOAuthStore.Entry clientEntry(McpOAuthStore.Entry current, McpOAuthStore.ClientInfo client)
				throws IOException {
			McpOAuthStore.Entry updated = current == null
					? new McpOAuthStore.Entry(null, client)
					: current.withClientInfo(client);
			store.write(name, config.url().toString(), updated);
			entry = updated;
			loaded = true;
			return updated;
		}

		private McpOAuthStore.ClientInfo clientInformation(
				McpOAuthStore.Entry current, Discovery discovery, URI redirectUri, String scope) throws Exception {
			if (settings.clientId() != null) {
				return new McpOAuthStore.ClientInfo(
						settings.clientId(), settings.clientSecret(), null, null, null, redirectUri.toString());
			}
			McpOAuthStore.ClientInfo stored = current == null ? null : current.clientInfo();
			if (usable(stored, redirectUri)) return stored;
			McpOAuthStore.ClientInfo registered = register(discovery, redirectUri, scope);
			clientEntry(current, registered);
			return registered;
		}

		private McpOAuthStore.Entry refresh(McpOAuthStore.Entry current, Challenge challenge) throws Exception {
			Discovery discovery = discover(config, challenge);
			McpOAuthStore.ClientInfo client = effectiveClient(current, settings.redirectUri());
			if (client == null) throw new OAuthFailure(400, "invalid_client", "No OAuth client is registered for this MCP server");
			LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
			parameters.put("grant_type", "refresh_token");
			parameters.put("refresh_token", current.tokens().refreshToken());
			McpOAuthStore.Tokens refreshed = requestTokens(
					discovery, client, parameters, current.tokens(), Map.of());
			McpOAuthStore.Entry updated = current.withTokens(refreshed);
			store.write(name, config.url().toString(), updated);
			entry = updated;
			loaded = true;
			return updated;
		}

		private McpOAuthStore.ClientInfo effectiveClient(McpOAuthStore.Entry current, URI redirectUri) {
			if (settings.clientId() != null) {
				return new McpOAuthStore.ClientInfo(
						settings.clientId(), settings.clientSecret(), null, null, null, redirectUri.toString());
			}
			McpOAuthStore.ClientInfo candidate = current == null ? null : current.clientInfo();
			return usable(candidate, redirectUri) ? candidate : null;
		}

		private boolean usable(McpOAuthStore.ClientInfo client, URI redirectUri) {
			if (client == null || client.clientId() == null || client.clientId().isBlank()) return false;
			Long expires = client.clientSecretExpiresAt();
			if (expires != null && expires > 0 && expires <= Instant.now().getEpochSecond()) return false;
			if (client.redirectUri() != null) return client.redirectUri().equals(redirectUri.toString());
			return settings.usesDefaultRedirect();
		}

		private McpOAuthStore.Entry load() throws IOException {
			return loaded ? entry : reload();
		}

		private McpOAuthStore.Entry reload() throws IOException {
			entry = store.read(name, config.url().toString());
			loaded = true;
			return entry;
		}

		private void invalidateAfterRefreshFailure(McpOAuthStore.Entry current, OAuthFailure failure)
				throws IOException {
			boolean invalidClient = "invalid_client".equals(failure.code())
					|| "unauthorized_client".equals(failure.code());
			McpOAuthStore.Entry cleared = invalidClient && settings.clientId() == null
					? new McpOAuthStore.Entry(null, null)
					: current.withTokens(null);
			store.write(name, config.url().toString(), cleared);
			entry = cleared;
			loaded = true;
		}

		private boolean expired(McpOAuthStore.Tokens tokens) {
			return tokens.expiresAt() != null
					&& tokens.expiresAt() <= Instant.now().getEpochSecond() + REFRESH_SKEW_SECONDS;
		}

		private McpOAuthStore.ClientInfo register(Discovery discovery, URI redirectUri, String scope) throws Exception {
			URI endpoint = discovery.metadata().registrationEndpoint();
			if (endpoint == null) {
				throw new IOException(
						"OAuth server does not support dynamic client registration; configure oauth.clientId for MCP server \""
								+ name + "\"");
			}
			ObjectNode request = Json.object();
			request.putArray("redirect_uris").add(redirectUri.toString());
			request.put("client_name", "codingagent");
			request.put("client_uri", "https://github.com/mikeyreilly/coding-agent");
			request.putArray("grant_types").add("authorization_code").add("refresh_token");
			request.putArray("response_types").add("code");
			request.put("token_endpoint_auth_method", "none");
			if (scope != null) request.put("scope", scope);
			Response response = send(postJson(endpoint, request, Map.of()));
			if (!response.success()) throw oauthFailure(response, "Dynamic OAuth client registration failed");
			JsonNode body = parseObject(response, "dynamic client registration");
			String clientId = requiredText(body, "client_id", "dynamic client registration");
			return new McpOAuthStore.ClientInfo(
					clientId,
					optionalText(body, "client_secret"),
					optionalLong(body, "client_id_issued_at"),
					optionalLong(body, "client_secret_expires_at"),
					optionalText(body, "token_endpoint_auth_method"),
					redirectUri.toString());
		}
	}

	static boolean isOAuthChallenge(Throwable error) {
		McpHttpException http = McpHttpException.find(error);
		return http != null && Challenge.from(http) != null;
	}

	static boolean isInsufficientScope(Throwable error) {
		McpHttpException http = McpHttpException.find(error);
		Challenge challenge = http == null ? null : Challenge.from(http);
		return challenge != null && challenge.insufficientScope();
	}

	private Discovery discover(McpServerConfig.Remote config, Challenge challenge) throws Exception {
		ResourceMetadata resourceMetadata = discoverResourceMetadata(config, challenge);
		URI authorizationServer = resourceMetadata != null && !resourceMetadata.authorizationServers().isEmpty()
				? resourceMetadata.authorizationServers().getFirst()
				: origin(config.url());
		requireSecureEndpoint(authorizationServer, "authorization server");
		AuthorizationMetadata metadata = discoverAuthorizationMetadata(authorizationServer, Map.of());
		if (metadata == null) {
			throw new IOException("OAuth authorization server metadata was not found for " + authorizationServer);
		}
		URI resource = resourceMetadata == null ? canonicalResource(config.url()) : resourceMetadata.resource();
		if (resourceMetadata != null && !resourceAllowed(config.url(), resource)) {
			throw new IOException("OAuth protected resource " + resource + " does not match MCP endpoint " + config.url());
		}
		return new Discovery(authorizationServer, metadata, resourceMetadata, resource);
	}

	private ResourceMetadata discoverResourceMetadata(McpServerConfig.Remote config, Challenge challenge)
			throws Exception {
		if (challenge != null && challenge.resourceMetadataUrl() != null) {
			URI endpoint = challenge.resourceMetadataUrl();
			requireSecureEndpoint(endpoint, "OAuth protected resource metadata");
			Response response = send(get(endpoint, Map.of(), true));
			if (!response.success()) {
				throw new IOException("OAuth protected resource metadata returned HTTP " + response.status() + " (" + endpoint + ")");
			}
			return parseResourceMetadata(response);
		}

		URI server = config.url();
		String path = server.getRawPath();
		if (path == null || path.isEmpty()) path = "/";
		if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
		URI pathAware = atOrigin(server, "/.well-known/oauth-protected-resource" + (path.equals("/") ? "" : path), server.getRawQuery());
		Response response = send(get(pathAware, Map.of(), true));
		if (response.success()) return parseResourceMetadata(response);
		if (!(response.status() >= 400 && response.status() < 500)) {
			throw new IOException("OAuth protected resource metadata returned HTTP " + response.status() + " (" + pathAware + ")");
		}
		if (!path.equals("/")) {
			URI root = atOrigin(server, "/.well-known/oauth-protected-resource", null);
			response = send(get(root, Map.of(), true));
			if (response.success()) return parseResourceMetadata(response);
			if (!(response.status() >= 400 && response.status() < 500)) {
				throw new IOException("OAuth protected resource metadata returned HTTP " + response.status() + " (" + root + ")");
			}
		}
		return null;
	}

	private ResourceMetadata parseResourceMetadata(Response response) throws IOException {
		JsonNode body = parseObject(response, "OAuth protected resource metadata");
		URI resource = requiredUri(body, "resource", "OAuth protected resource metadata");
		List<URI> servers = uriArray(body.get("authorization_servers"), "authorization_servers");
		List<String> scopes = textArray(body.get("scopes_supported"), "scopes_supported");
		return new ResourceMetadata(resource, servers, scopes);
	}

	private AuthorizationMetadata discoverAuthorizationMetadata(URI server, Map<String, String> headers)
			throws Exception {
		for (URI endpoint : authorizationMetadataUrls(server)) {
			Response response = send(get(endpoint, headers, true));
			if (response.success()) return parseAuthorizationMetadata(response);
			if (response.status() >= 400 && response.status() < 500) continue;
			throw new IOException("OAuth authorization metadata returned HTTP " + response.status() + " (" + endpoint + ")");
		}
		return null;
	}

	private AuthorizationMetadata parseAuthorizationMetadata(Response response) throws IOException {
		JsonNode body = parseObject(response, "OAuth authorization server metadata");
		requiredText(body, "issuer", "OAuth authorization server metadata");
		URI authorization = requiredUri(body, "authorization_endpoint", "OAuth authorization server metadata");
		URI token = requiredUri(body, "token_endpoint", "OAuth authorization server metadata");
		URI registration = optionalUri(body, "registration_endpoint", "OAuth authorization server metadata");
		requireSecureEndpoint(authorization, "OAuth authorization endpoint");
		requireSecureEndpoint(token, "OAuth token endpoint");
		if (registration != null) requireSecureEndpoint(registration, "OAuth registration endpoint");
		List<String> responseTypes = textArray(body.get("response_types_supported"), "response_types_supported");
		if (!responseTypes.contains("code")) throw new IOException("OAuth server does not support authorization code responses");
		List<String> challengeMethods = textArray(body.get("code_challenge_methods_supported"), "code_challenge_methods_supported");
		if (!challengeMethods.contains("S256")) throw new IOException("OAuth server does not advertise PKCE S256 support");
		return new AuthorizationMetadata(
				authorization,
				token,
				registration,
				responseTypes,
				challengeMethods,
				textArray(body.get("token_endpoint_auth_methods_supported"), "token_endpoint_auth_methods_supported"),
				textArray(body.get("scopes_supported"), "scopes_supported"));
	}

	private URI authorizationUrl(
			Discovery discovery,
			McpOAuthStore.ClientInfo client,
			URI redirectUri,
			String state,
			String codeChallenge,
			String scope) {
		LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
		parameters.put("response_type", "code");
		parameters.put("client_id", client.clientId());
		parameters.put("code_challenge", codeChallenge);
		parameters.put("code_challenge_method", "S256");
		parameters.put("redirect_uri", redirectUri.toString());
		parameters.put("state", state);
		if (scope != null) parameters.put("scope", scope);
		if (scope != null && List.of(scope.split("\\s+")).contains("offline_access")) parameters.put("prompt", "consent");
		parameters.put("resource", discovery.resource().toString());
		return appendQuery(discovery.metadata().authorizationEndpoint(), parameters);
	}

	private McpOAuthStore.Tokens exchangeCode(
			Discovery discovery,
			McpOAuthStore.ClientInfo client,
			String code,
			String verifier,
			URI redirectUri,
			Map<String, String> configuredHeaders) throws Exception {
		LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
		parameters.put("grant_type", "authorization_code");
		parameters.put("code", code);
		parameters.put("code_verifier", verifier);
		parameters.put("redirect_uri", redirectUri.toString());
		return requestTokens(discovery, client, parameters, null, configuredHeaders);
	}

	private McpOAuthStore.Tokens requestTokens(
			Discovery discovery,
			McpOAuthStore.ClientInfo client,
			LinkedHashMap<String, String> parameters,
			McpOAuthStore.Tokens previous,
			Map<String, String> configuredHeaders) throws Exception {
		parameters.put("resource", discovery.resource().toString());
		LinkedHashMap<String, String> headers = new LinkedHashMap<>(configuredHeaders);
		headers.put("Accept", "application/json");
		applyClientAuthentication(discovery.metadata(), client, parameters, headers);
		HttpRequest request = postForm(discovery.metadata().tokenEndpoint(), parameters, headers);
		Response response = send(request);
		if (!response.success()) throw oauthFailure(response, "OAuth token request failed");
		JsonNode body = parseObject(response, "OAuth token response");
		String access = requiredText(body, "access_token", "OAuth token response");
		String tokenType = requiredText(body, "token_type", "OAuth token response");
		if (!tokenType.equalsIgnoreCase("Bearer")) throw new IOException("OAuth token response did not return a Bearer token");
		String refresh = optionalText(body, "refresh_token");
		if (refresh == null && previous != null) refresh = previous.refreshToken();
		Long expiresIn = optionalLong(body, "expires_in");
		if (expiresIn != null && expiresIn < 0) throw new IOException("OAuth token response contains a negative expires_in");
		Long expiresAt = expiresIn == null ? null : Instant.now().getEpochSecond() + expiresIn;
		String scope = optionalText(body, "scope");
		if (scope == null && previous != null) scope = previous.scope();
		return new McpOAuthStore.Tokens(access, refresh, expiresAt, scope);
	}

	private static void applyClientAuthentication(
			AuthorizationMetadata metadata,
			McpOAuthStore.ClientInfo client,
			Map<String, String> parameters,
			Map<String, String> headers) throws IOException {
		String method = selectClientAuthMethod(metadata, client);
		switch (method) {
			case "client_secret_basic" -> {
				if (client.clientSecret() == null) throw new IOException("OAuth client_secret_basic requires a client secret");
				String value = client.clientId() + ":" + client.clientSecret();
				headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
			}
			case "client_secret_post" -> {
				parameters.put("client_id", client.clientId());
				if (client.clientSecret() != null) parameters.put("client_secret", client.clientSecret());
			}
			case "none" -> parameters.put("client_id", client.clientId());
			default -> throw new IOException("Unsupported OAuth token endpoint authentication method: " + method);
		}
	}

	private static String selectClientAuthMethod(AuthorizationMetadata metadata, McpOAuthStore.ClientInfo client) {
		List<String> supported = metadata.tokenAuthMethods();
		String registered = client.tokenEndpointAuthMethod();
		if (registered != null
				&& List.of("client_secret_basic", "client_secret_post", "none").contains(registered)
				&& (supported.isEmpty() || supported.contains(registered))) return registered;
		if (supported.isEmpty()) return client.clientSecret() == null ? "none" : "client_secret_basic";
		if (client.clientSecret() != null && supported.contains("client_secret_basic")) return "client_secret_basic";
		if (client.clientSecret() != null && supported.contains("client_secret_post")) return "client_secret_post";
		if (supported.contains("none")) return "none";
		return client.clientSecret() == null ? "none" : "client_secret_post";
	}

	private HttpRequest get(URI uri, Map<String, String> configuredHeaders, boolean metadata) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET();
		if (metadata) {
			builder.setHeader("Accept", "application/json");
			builder.setHeader("MCP-Protocol-Version", PROTOCOL_VERSION);
		}
		applyHeaders(builder, configuredHeaders);
		return builder.build();
	}

	private HttpRequest postJson(URI uri, JsonNode body, Map<String, String> configuredHeaders) throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(HTTP_TIMEOUT)
				.setHeader("Accept", "application/json")
				.setHeader("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
		applyHeaders(builder, configuredHeaders);
		return builder.build();
	}

	private HttpRequest postForm(URI uri, Map<String, String> parameters, Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(HTTP_TIMEOUT)
				.setHeader("Accept", "application/json")
				.setHeader("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form(parameters), StandardCharsets.UTF_8));
		applyHeaders(builder, headers);
		return builder.build();
	}

	private Response send(HttpRequest request) throws IOException, InterruptedException {
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Response(response.statusCode(), response.uri(), response.headers().map(), response.body());
	}

	private static void applyHeaders(HttpRequest.Builder request, Map<String, String> headers) {
		for (var header : headers.entrySet()) {
			String name = header.getKey();
			if (!restricted(name) && !name.equalsIgnoreCase("Content-Type") && !name.equalsIgnoreCase("Accept")) {
				request.header(name, header.getValue());
			}
		}
	}

	private static boolean restricted(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "content-length", "host", "connection", "upgrade" -> true;
			default -> false;
		};
	}

	private static OAuthFailure oauthFailure(Response response, String fallback) {
		String code = null;
		String description = null;
		try {
			JsonNode node = Json.MAPPER.readTree(response.body());
			code = optionalText(node, "error");
			description = optionalText(node, "error_description");
		} catch (IOException ignored) {}
		String message = description != null ? description : code != null ? code : abbreviate(response.body());
		if (message == null || message.isBlank()) message = fallback;
		return new OAuthFailure(response.status(), code, fallback + ": " + message);
	}

	private static boolean invalidCredential(OAuthFailure error) {
		return error.code() != null && List.of("invalid_grant", "invalid_client", "unauthorized_client").contains(error.code());
	}

	private static JsonNode parseObject(Response response, String source) throws IOException {
		JsonNode body;
		try {
			body = Json.MAPPER.readTree(response.body());
		} catch (IOException error) {
			throw new IOException("Invalid JSON in " + source + " from " + response.uri(), error);
		}
		if (body == null || !body.isObject()) throw new IOException("Invalid " + source + ": expected a JSON object");
		return body;
	}

	private static String requiredText(JsonNode node, String field, String source) throws IOException {
		String value = optionalText(node, field);
		if (value == null) throw new IOException("Invalid " + source + ": missing " + field);
		return value;
	}

	private static String optionalText(JsonNode node, String field) {
		if (node == null) return null;
		JsonNode value = node.get(field);
		return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
	}

	private static Long optionalLong(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) return null;
		if (value.isNumber()) return (long) Math.floor(value.asDouble());
		if (value.isTextual()) {
			try {
				return Long.parseLong(value.asText());
			} catch (NumberFormatException ignored) {}
		}
		throw new IOException("Invalid OAuth field " + field + ": expected a number");
	}

	private static URI requiredUri(JsonNode node, String field, String source) throws IOException {
		String value = requiredText(node, field, source);
		return parseAbsoluteHttpUri(value, source + "." + field);
	}

	private static URI optionalUri(JsonNode node, String field, String source) throws IOException {
		String value = optionalText(node, field);
		return value == null ? null : parseAbsoluteHttpUri(value, source + "." + field);
	}

	private static URI parseAbsoluteHttpUri(String value, String field) throws IOException {
		try {
			URI uri = URI.create(value);
			if (!uri.isAbsolute()
					|| uri.getHost() == null
					|| uri.getUserInfo() != null
					|| uri.getFragment() != null
					|| !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
				throw new IllegalArgumentException();
			}
			return uri;
		} catch (IllegalArgumentException error) {
			throw new IOException("Invalid " + field + ": expected an absolute HTTP(S) URL", error);
		}
	}

	private static List<String> textArray(JsonNode node, String field) throws IOException {
		if (node == null || node.isNull()) return List.of();
		if (!node.isArray()) throw new IOException("Invalid OAuth metadata field " + field + ": expected an array");
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			if (!value.isTextual() || value.asText().isBlank()) {
				throw new IOException("Invalid OAuth metadata field " + field + ": expected strings");
			}
			values.add(value.asText());
		}
		return List.copyOf(values);
	}

	private static List<URI> uriArray(JsonNode node, String field) throws IOException {
		List<String> values = textArray(node, field);
		List<URI> result = new ArrayList<>();
		for (String value : values) result.add(parseAbsoluteHttpUri(value, field));
		return List.copyOf(result);
	}

	private static List<URI> authorizationMetadataUrls(URI server) {
		String path = server.getRawPath();
		if (path == null || path.isEmpty() || path.equals("/")) {
			return List.of(
					atOrigin(server, "/.well-known/oauth-authorization-server", null),
					atOrigin(server, "/.well-known/openid-configuration", null));
		}
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		return List.of(
				atOrigin(server, "/.well-known/oauth-authorization-server" + path, null),
				atOrigin(server, "/.well-known/openid-configuration" + path, null),
				atOrigin(server, path + "/.well-known/openid-configuration", null));
	}

	private static URI canonicalResource(URI server) {
		String value = server.toString();
		int fragment = value.indexOf('#');
		return fragment < 0 ? server : URI.create(value.substring(0, fragment));
	}

	private static boolean resourceAllowed(URI requested, URI configured) {
		if (!origin(requested).equals(origin(configured))) return false;
		String requestedPath = normalizedResourcePath(requested.getPath());
		String configuredPath = normalizedResourcePath(configured.getPath());
		return requestedPath.startsWith(configuredPath);
	}

	private static String normalizedResourcePath(String path) {
		String value = path == null || path.isEmpty() ? "/" : path;
		return value.endsWith("/") ? value : value + "/";
	}

	private static URI origin(URI uri) {
		return atOrigin(uri, "/", null);
	}

	private static URI atOrigin(URI uri, String path, String query) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		StringBuilder value = new StringBuilder()
				.append(uri.getScheme().toLowerCase(Locale.ROOT))
				.append("://")
				.append(host.indexOf(':') >= 0 ? "[" + host + "]" : host);
		if (uri.getPort() >= 0) value.append(':').append(uri.getPort());
		value.append(path);
		if (query != null && !query.isEmpty()) value.append('?').append(query);
		return URI.create(value.toString());
	}

	private static URI appendQuery(URI base, Map<String, String> parameters) {
		String value = base.toString();
		int fragment = value.indexOf('#');
		if (fragment >= 0) value = value.substring(0, fragment);
		String separator = base.getRawQuery() == null ? "?" : "&";
		return URI.create(value + separator + form(parameters));
	}

	private static String form(Map<String, String> values) {
		return values.entrySet().stream()
				.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
				.reduce((left, right) -> left + "&" + right)
				.orElse("");
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String randomUrlToken(int bytes) {
		byte[] value = new byte[bytes];
		random.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}

	private static void requireSecureEndpoint(URI uri, String description) throws IOException {
		if (uri.getScheme().equalsIgnoreCase("https")) return;
		if (uri.getScheme().equalsIgnoreCase("http") && loopback(uri.getHost())) return;
		throw new IOException(description + " must use HTTPS: " + uri);
	}

	private static boolean loopback(String host) {
		return host != null
				&& (host.equalsIgnoreCase("localhost") || host.equals("::1") || host.startsWith("127."));
	}

	private static boolean hasAuthorization(Map<String, String> headers) {
		return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Authorization"));
	}

	private static String selectScope(Challenge challenge, ResourceMetadata resource, String configured) {
		if (challenge != null && challenge.scope() != null) return challenge.scope();
		if (resource != null && !resource.scopes().isEmpty()) return String.join(" ", resource.scopes());
		return configured;
	}

	private static String abbreviate(String value) {
		if (value == null) return "";
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
	}

	private record Response(int status, URI uri, Map<String, List<String>> headers, String body) {
		boolean success() {
			return status >= 200 && status < 300;
		}
	}

	private record ResourceMetadata(URI resource, List<URI> authorizationServers, List<String> scopes) {}

	private record AuthorizationMetadata(
			URI authorizationEndpoint,
			URI tokenEndpoint,
			URI registrationEndpoint,
			List<String> responseTypes,
			List<String> challengeMethods,
			List<String> tokenAuthMethods,
			List<String> scopes) {}

	private record Discovery(
			URI authorizationServer,
			AuthorizationMetadata metadata,
			ResourceMetadata resourceMetadata,
			URI resource) {}

	private record Challenge(URI resourceMetadataUrl, String scope, String error) {
		static Challenge from(McpHttpException response) {
			String authenticate = response.header("WWW-Authenticate");
			if (response.status() != 401 && response.status() != 403) return null;
			if (authenticate != null && !authenticate.toLowerCase(Locale.ROOT).contains("bearer")) return null;
			Map<String, String> fields = parameters(authenticate);
			String error = fields.get("error");
			if (response.status() == 403 && !"insufficient_scope".equals(error)) return null;
			URI resource = null;
			String rawResource = fields.get("resource_metadata");
			if (rawResource != null) {
				try {
					resource = URI.create(rawResource);
					if (!resource.isAbsolute() || resource.getHost() == null) resource = null;
				} catch (IllegalArgumentException ignored) {}
			}
			return new Challenge(resource, fields.get("scope"), error);
		}

		boolean insufficientScope() {
			return "insufficient_scope".equals(error);
		}

		private static Map<String, String> parameters(String header) {
			if (header == null) return Map.of();
			LinkedHashMap<String, String> values = new LinkedHashMap<>();
			Matcher matcher = AUTH_PARAMETER.matcher(header);
			while (matcher.find()) {
				values.put(
						matcher.group(1).toLowerCase(Locale.ROOT),
						matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
			}
			return values;
		}
	}

	private record OAuthSettings(
			String clientId,
			String clientSecret,
			String scope,
			Integer callbackPort,
			URI configuredRedirectUri) {
		static OAuthSettings parse(JsonNode node) {
			if (node == null || !node.isObject()) return new OAuthSettings(null, null, null, null, null);
			Integer port = node.path("callbackPort").isIntegralNumber() ? node.path("callbackPort").asInt() : null;
			URI redirect = node.path("redirectUri").isTextual() ? URI.create(node.path("redirectUri").asText()) : null;
			return new OAuthSettings(
					optionalText(node, "clientId"),
					optionalText(node, "clientSecret"),
					optionalText(node, "scope"),
					port,
					redirect);
		}

		static boolean disabled(JsonNode node) {
			return node != null && node.isBoolean() && !node.asBoolean();
		}

		URI redirectUri() {
			if (configuredRedirectUri != null) return configuredRedirectUri;
			int port = callbackPort == null ? DEFAULT_CALLBACK_PORT : callbackPort;
			return URI.create("http://127.0.0.1:" + port + DEFAULT_CALLBACK_PATH);
		}

		boolean usesDefaultRedirect() {
			return configuredRedirectUri == null && callbackPort == null;
		}
	}

	private static final class OAuthFailure extends IOException {
		private final int status;
		private final String code;

		OAuthFailure(int status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}

		int status() {
			return status;
		}

		String code() {
			return code;
		}
	}
}
