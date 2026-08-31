package com.quaxt.codingagent.ai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.providers.ProviderState;

class GitHubCopilotAuthTest {
	@TempDir Path tempDir;

	@Test
	void completesDeviceLoginMintsCopilotTokenAndPersistsEnabledModels() throws Exception {
		AtomicInteger tokenRequests = new AtomicInteger();
		AtomicInteger policyRequests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/login/device/code", exchange -> writeJson(exchange, """
				{"device_code":"device","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":0}
				"""));
		server.createContext("/login/oauth/access_token", exchange -> {
			assertTrue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).contains("device_code=device"));
			writeJson(exchange, "{\"access_token\":\"github-token\"}");
		});
		server.createContext("/copilot_internal/v2/token", exchange -> {
			assertEquals("token github-token", exchange.getRequestHeaders().getFirst("Authorization"));
			tokenRequests.incrementAndGet();
			writeJson(exchange, "{\"token\":\"copilot-token\",\"expires_at\":4102444800}");
		});
		server.createContext("/models", exchange -> {
			assertEquals("Bearer copilot-token", exchange.getRequestHeaders().getFirst("Authorization"));
			writeJson(exchange, """
					{"data":[
					  {"id":"gpt-5.4","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":true}}},
					  {"id":"disabled","model_picker_enabled":false,"policy":{"state":"disabled"},"capabilities":{"supports":{"tool_calls":true}}},
					  {"id":"no-tools","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":false}}}
					]}
					""");
		});
		server.createContext("/models/gpt-5.4/policy", exchange -> {
			assertEquals("Bearer copilot-token", exchange.getRequestHeaders().getFirst("Authorization"));
			assertEquals("chat-policy", exchange.getRequestHeaders().getFirst("openai-intent"));
			assertEquals("{\"state\":\"enabled\"}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			policyRequests.incrementAndGet();
			writeJson(exchange, "{}");
		});
		server.start();
		try {
			URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			CodingAgentOperations store = CodingAgentOperations.INSTANCE;
			store.fileCredentialStore(tempDir.resolve("auth.json"), null);
			ProviderState state = ProviderState.GITHUB_COPILOT_OPERATIONS;
			CodingAgentOperations auth = CodingAgentOperations.INSTANCE;
			auth.gitHubCopilotAuth(
					state, store, base, base.resolve("/copilot_internal/v2/token"), base);

			CodingAgentOperations.GitHubCopilotDeviceCode device = auth.gitHubCopilotBeginLogin(state);
			assertEquals("ABCD-EFGH", device.userCode);
			Credential.OAuthCredential credential = auth.gitHubCopilotCompleteLogin(state, device);

			assertEquals("copilot-token", credential.access);
			assertEquals(java.util.List.of("gpt-5.4"), credential.availableModelIds);
			assertEquals(1, auth.gitHubCopilotEnableModels(state, java.util.List.of("gpt-5.4")));
			assertEquals(
					java.util.List.of("gpt-5.4"),
					auth.gitHubCopilotRefreshAvailableModels(state).availableModelIds);
			assertEquals("copilot-token", auth.gitHubCopilotResolveToken(state).accessToken);
			assertEquals(1, tokenRequests.get());
			assertEquals(1, policyRequests.get());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void reportsCopilotTokenHttpFailuresAsIoErrors() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/copilot_internal/v2/token", exchange -> {
			byte[] body = "<html><title>Unicorn!</title></html>".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "text/html");
			exchange.sendResponseHeaders(502, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			CodingAgentOperations store = CodingAgentOperations.INSTANCE;
			store.fileCredentialStore(tempDir.resolve("failing-auth.json"), null);
			CodingAgentOperations.INSTANCE.modifyCredential(
					store,
					CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID,
					ignored -> new Credential.OAuthCredential(
							"expired-token", "github-token", 0, null, Map.of()));
			ProviderState state = ProviderState.GITHUB_COPILOT_OPERATIONS;
			CodingAgentOperations auth = CodingAgentOperations.INSTANCE;
			auth.gitHubCopilotAuth(
					state, store, base, base.resolve("/copilot_internal/v2/token"), base);

			IOException error = assertThrows(IOException.class, () -> auth.gitHubCopilotResolveToken(state));

			assertTrue(error.getMessage().startsWith("502:"));
		} finally {
			server.stop(0);
		}
	}

	private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String value) throws java.io.IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
