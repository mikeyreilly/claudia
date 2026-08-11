package com.quaxt.codingagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

class McpOAuthTest {
	@TempDir Path tempDir;

	@Test
	void authorizesPersistsAndRefreshesARemoteServer() throws Exception {
		int callbackPort = availablePort();
		AtomicInteger browserOpens = new AtomicInteger();
		AtomicInteger registrations = new AtomicInteger();
		AtomicInteger refreshes = new AtomicInteger();
		AtomicReference<String> acceptedToken = new AtomicReference<>("access-one");
		AtomicReference<URI> base = new AtomicReference<>();

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		base.set(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
		server.createContext("/mcp", exchange -> handleMcp(exchange, base.get(), acceptedToken.get()));
		server.createContext("/.well-known/oauth-protected-resource", exchange -> {
			ObjectNode metadata = Json.object().put("resource", base.get() + "/mcp");
			metadata.putArray("authorization_servers").add(base.get().toString());
			metadata.putArray("scopes_supported").add("metadata-scope");
			json(exchange, 200, metadata);
		});
		server.createContext("/.well-known/oauth-authorization-server", exchange -> {
			ObjectNode metadata = Json.object()
					.put("issuer", base.get().toString())
					.put("authorization_endpoint", base.get() + "/authorize")
					.put("token_endpoint", base.get() + "/token")
					.put("registration_endpoint", base.get() + "/register");
			metadata.putArray("response_types_supported").add("code");
			metadata.putArray("grant_types_supported").add("authorization_code").add("refresh_token");
			metadata.putArray("token_endpoint_auth_methods_supported").add("none");
			metadata.putArray("code_challenge_methods_supported").add("S256");
			json(exchange, 200, metadata);
		});
		server.createContext("/register", exchange -> {
			registrations.incrementAndGet();
			JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
			assertEquals("challenge-scope", request.path("scope").asText());
			assertEquals("none", request.path("token_endpoint_auth_method").asText());
			json(exchange, 201, Json.object()
					.put("client_id", "dynamic-client")
					.put("token_endpoint_auth_method", "none"));
		});
		server.createContext("/token", exchange -> {
			Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			assertEquals("dynamic-client", form.get("client_id"));
			assertEquals(base.get() + "/mcp", form.get("resource"));
			if (form.get("grant_type").equals("refresh_token")) {
				int refresh = refreshes.incrementAndGet();
				assertEquals("refresh-one", form.get("refresh_token"));
				String access = refresh == 1 ? "access-two" : "access-three";
				acceptedToken.set(access);
				json(exchange, 200, Json.object()
						.put("access_token", access)
						.put("token_type", "Bearer")
						.put("expires_in", 3600));
				return;
			}
			assertEquals("authorization_code", form.get("grant_type"));
			assertEquals("test-code", form.get("code"));
			assertNotNull(form.get("code_verifier"));
			json(exchange, 200, Json.object()
					.put("access_token", "access-one")
					.put("refresh_token", "refresh-one")
					.put("token_type", "Bearer")
					.put("expires_in", 3600)
					.put("scope", "challenge-scope"));
		});
		server.start();

		try {
			McpOAuthStore store = new McpOAuthStore(tempDir.resolve("mcp-auth.json"), List.of());
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
			McpOAuthClient oauth = new McpOAuthClient(store, http, authorizationUrl -> {
				browserOpens.incrementAndGet();
				Map<String, String> query = form(authorizationUrl.getRawQuery());
				assertEquals("dynamic-client", query.get("client_id"));
				assertEquals("S256", query.get("code_challenge_method"));
				assertEquals("challenge-scope", query.get("scope"));
				assertEquals(base.get() + "/mcp", query.get("resource"));
				URI redirect = URI.create(query.get("redirect_uri") + "?code="
						+ encode("test-code") + "&state=" + encode(query.get("state")));
				Thread.ofVirtual().start(() -> {
					try {
						http.send(HttpRequest.newBuilder(redirect).GET().build(), HttpResponse.BodyHandlers.discarding());
					} catch (Exception error) {
						throw new RuntimeException(error);
					}
				});
				return true;
			}, Duration.ofSeconds(5));

			ObjectNode oauthConfig = Json.object().put("callbackPort", callbackPort);
			McpServerConfig.Remote remote = new McpServerConfig.Remote(
					base.get().resolve("/mcp"), Map.of(), oauthConfig, true, 5_000L);
			McpConfiguration configuration = new McpConfiguration(Map.of("protected", remote), List.of());

			try (McpManager manager = new McpManager(configuration, tempDir, oauth)) {
				manager.awaitReady();
				assertEquals(McpManager.State.AUTH_REQUIRED, manager.status("protected").state());
				assertEquals(McpManager.State.CONNECTED, manager.connect("protected").state());
				assertEquals(1, manager.status("protected").toolCount());
			}
			assertEquals(1, browserOpens.get());
			assertEquals(1, registrations.get());
			assertTrue(Files.isRegularFile(tempDir.resolve("mcp-auth.json")));
			McpOAuthStore.Entry saved = store.read("protected", base.get().resolve("/mcp").toString());
			assertEquals("access-one", saved.tokens().accessToken());
			assertEquals("dynamic-client", saved.clientInfo().clientId());

			// Force the access token to expire. Startup must use the refresh token without reopening a browser.
			store.write(
					"protected",
					base.get().resolve("/mcp").toString(),
					saved.withTokens(new McpOAuthStore.Tokens(
							"access-one", "refresh-one", Instant.now().getEpochSecond() - 1, "challenge-scope")));
			try (McpManager manager = new McpManager(configuration, tempDir, oauth)) {
				manager.awaitReady();
				assertEquals(McpManager.State.CONNECTED, manager.status("protected").state());

				// A token rejected before its recorded expiry is refreshed and the same tool request is retried once.
				acceptedToken.set("access-three");
				AgentTool.ToolResult result = manager.tools().getFirst().execute(
						"call-1", Json.object().put("value", "renewed"), new AbortSignal(), ignored -> {});
				assertEquals("renewed", ((TextContent) result.content().getFirst()).text());
			}
			assertEquals(1, browserOpens.get());
			assertEquals(2, refreshes.get());
			assertEquals("access-three", store.read("protected", base.get().resolve("/mcp").toString()).tokens().accessToken());
		} finally {
			server.stop(0);
		}
	}

	private static void handleMcp(HttpExchange exchange, URI base, String token) throws IOException {
		try (exchange) {
			if (exchange.getRequestMethod().equals("DELETE")) {
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
				exchange.getResponseHeaders().set(
						"WWW-Authenticate",
						"Bearer realm=\"mcp\", resource_metadata=\"" + base
								+ "/.well-known/oauth-protected-resource\", scope=\"challenge-scope\"");
				byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(401, body.length);
				exchange.getResponseBody().write(body);
				return;
			}
			JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
			if (!request.has("id")) {
				exchange.sendResponseHeaders(202, -1);
				return;
			}
			ObjectNode response = Json.object().put("jsonrpc", "2.0");
			response.set("id", request.get("id"));
			switch (request.path("method").asText()) {
				case "initialize" -> response.putObject("result")
						.put("protocolVersion", "2025-11-25")
						.putObject("capabilities")
						.putObject("tools");
				case "tools/list" -> response.putObject("result").putArray("tools").addObject()
						.put("name", "oauth_echo")
						.putObject("inputSchema")
						.put("type", "object")
						.putObject("properties");
				case "tools/call" -> response.putObject("result").putArray("content").addObject()
						.put("type", "text")
						.put("text", request.path("params").path("arguments").path("value").asText());
				default -> response.putObject("error").put("code", -32601).put("message", "not found");
			}
			json(exchange, 200, response);
		}
	}

	private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
		byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static Map<String, String> form(String body) {
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		if (body == null || body.isBlank()) return result;
		for (String pair : body.split("&")) {
			String[] parts = pair.split("=", 2);
			result.put(
					URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
					URLDecoder.decode(parts.length == 1 ? "" : parts[1], StandardCharsets.UTF_8));
		}
		return result;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static int availablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
