package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Carrier for OAuth 2.1 authorization-code, PKCE, dynamic-registration, and
 * refresh support for remote MCP servers: the credential store, the HTTP
 * client, the browser launcher, and the lock serializing interactive flows.
 * All of the protocol behavior lives in CodingAgentOperations.
 */
public final class McpOAuthClient {
	public static final int DEFAULT_CALLBACK_PORT = 19_876;
	public static final String DEFAULT_CALLBACK_PATH = "/mcp/oauth/callback";
	public static final Duration DEFAULT_CALLBACK_TIMEOUT = Duration.ofMinutes(5);
	public static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
	public static final long REFRESH_SKEW_SECONDS = 30;
	public static final String PROTOCOL_VERSION = "2025-11-25";
	public static final Pattern AUTH_PARAMETER = Pattern.compile(
			"(?i)(?:^|[,\\s])([a-z][a-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

	public McpOAuthStore store;
	public HttpClient http;
	/** Opens the authorization URL in the host browser and reports whether it launched. */
	public Predicate<URI> browser;
	public Duration callbackTimeout;
	public SecureRandom random = new SecureRandom();
	public ReentrantLock interactiveLock = new ReentrantLock();

	public McpOAuthClient(McpOAuthStore store, HttpClient http, Predicate<URI> browser, Duration callbackTimeout) {
		this.store = store;
		this.http = http;
		this.browser = browser;
		this.callbackTimeout = callbackTimeout;
	}

	/** Per-server OAuth state; the session instance is its own monitor. */
	public static final class Session {
		public McpOAuthClient client;
		public String name;
		public McpServerConfig.Remote config;
		public OAuthSettings settings;
		public McpOAuthStore.Entry entry;
		public boolean loaded;

		public Session(McpOAuthClient client, String name, McpServerConfig.Remote config, OAuthSettings settings) {
			this.client = client;
			this.name = name;
			this.config = config;
			this.settings = settings;
		}
	}

	/** One completed OAuth HTTP exchange. */
	public static final class Response {
		public int status;
		public URI uri;
		public Map<String, List<String>> headers;
		public String body;

		public Response(int status, URI uri, Map<String, List<String>> headers, String body) {
			this.status = status;
			this.uri = uri;
			this.headers = headers;
			this.body = body;
		}
	}

	/** Protected-resource metadata advertised by an MCP server. */
	public static final class ResourceMetadata {
		public URI resource;
		public List<URI> authorizationServers;
		public List<String> scopes;

		public ResourceMetadata(URI resource, List<URI> authorizationServers, List<String> scopes) {
			this.resource = resource;
			this.authorizationServers = authorizationServers;
			this.scopes = scopes;
		}
	}

	/** Authorization-server metadata used to drive the code flow. */
	public static final class AuthorizationMetadata {
		public URI authorizationEndpoint;
		public URI tokenEndpoint;
		public URI registrationEndpoint;
		public List<String> responseTypes;
		public List<String> challengeMethods;
		public List<String> tokenAuthMethods;
		public List<String> scopes;

		public AuthorizationMetadata(
				URI authorizationEndpoint,
				URI tokenEndpoint,
				URI registrationEndpoint,
				List<String> responseTypes,
				List<String> challengeMethods,
				List<String> tokenAuthMethods,
				List<String> scopes) {
			this.authorizationEndpoint = authorizationEndpoint;
			this.tokenEndpoint = tokenEndpoint;
			this.registrationEndpoint = registrationEndpoint;
			this.responseTypes = responseTypes;
			this.challengeMethods = challengeMethods;
			this.tokenAuthMethods = tokenAuthMethods;
			this.scopes = scopes;
		}
	}

	/** The resolved authorization server, its metadata, and the canonical resource. */
	public static final class Discovery {
		public URI authorizationServer;
		public AuthorizationMetadata metadata;
		public ResourceMetadata resourceMetadata;
		public URI resource;

		public Discovery(
				URI authorizationServer,
				AuthorizationMetadata metadata,
				ResourceMetadata resourceMetadata,
				URI resource) {
			this.authorizationServer = authorizationServer;
			this.metadata = metadata;
			this.resourceMetadata = resourceMetadata;
			this.resource = resource;
		}
	}

	/** A parsed {@code WWW-Authenticate} bearer challenge. */
	public static final class Challenge {
		public URI resourceMetadataUrl;
		public String scope;
		public String error;

		public Challenge(URI resourceMetadataUrl, String scope, String error) {
			this.resourceMetadataUrl = resourceMetadataUrl;
			this.scope = scope;
			this.error = error;
		}
	}

	/** The {@code oauth} block configured for one remote MCP server. */
	public static final class OAuthSettings {
		public String clientId;
		public String clientSecret;
		public String scope;
		public Integer callbackPort;
		public URI configuredRedirectUri;

		public OAuthSettings(
				String clientId, String clientSecret, String scope, Integer callbackPort, URI configuredRedirectUri) {
			this.clientId = clientId;
			this.clientSecret = clientSecret;
			this.scope = scope;
			this.callbackPort = callbackPort;
			this.configuredRedirectUri = configuredRedirectUri;
		}
	}

	/** An OAuth error response with its RFC 6749 error code. */
	public static final class OAuthFailure extends IOException {
		public int status;
		public String code;

		public OAuthFailure(int status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}
	}
}
