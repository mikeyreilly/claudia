package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.FileCredentialStore;
import com.quaxt.codingagent.ai.auth.GitHubCopilotAuth;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

class GitHubCopilotProviderTest {
	private static StreamOptions options(String apiKey) {
		StreamOptions options = new StreamOptions();
		options.apiKey = apiKey;
		return options;
	}

	private static StreamOptions options(String apiKey, ThinkingLevel reasoning) {
		StreamOptions options = options(apiKey);
		options.reasoning = reasoning;
		return options;
	}

	@Test
	void routesAllCopilotWireProtocolsWithBearerAuthentication() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			assertBearer(exchange);
			writeSse(exchange, """
					event: message_start
					data: {"message":{"id":"a","usage":{"input_tokens":1}}}

					event: content_block_start
					data: {"index":0,"content_block":{"type":"text"}}

					event: content_block_delta
					data: {"index":0,"delta":{"type":"text_delta","text":"anthropic"}}

					event: message_stop
					data: {}

					""");
		});
		server.createContext("/chat/completions", exchange -> {
			assertBearer(exchange);
			writeSse(exchange, """
					data: {"choices":[{"delta":{"content":"completions"},"finish_reason":"stop"}]}

					data: [DONE]

					""");
		});
		server.createContext("/responses", exchange -> {
			assertBearer(exchange);
			writeSse(exchange, """
					data: {"type":"response.output_item.added","item":{"id":"m","type":"message"}}

					data: {"type":"response.output_text.delta","item_id":"m","delta":"responses"}

					data: {"type":"response.completed","response":{"id":"r","status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}

					""");
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			List<Model> models = List.of(
					model("anthropic", "anthropic-messages", base),
					model("completions", "openai-completions", base),
					model("responses", "openai-responses", base));
			FileCredentialStore store = CodingAgentOperations.fileCredentialStore(Files.createTempDirectory("copilot-auth").resolve("auth.json"));
			CodingAgentOperations.modifyCredential(
					store,
					GitHubCopilotAuth.PROVIDER_ID,
					ignored -> CodingAgentOperations.oauthCredential("copilot-token", "github-token", Long.MAX_VALUE, null));
			GitHubCopilotProvider provider = CodingAgentOperations.newGitHubCopilotProvider(
					models,
					CodingAgentOperations.gitHubCopilotAuth(store, URI.create(base), URI.create(base + "/token"), URI.create(base)));

			assertEquals("anthropic", CodingAgentOperations.text(
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, models.get(0), new Context(), new StreamOptions()))));
			assertEquals("completions", CodingAgentOperations.text(
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, models.get(1), new Context(), new StreamOptions()))));
			assertEquals("responses", CodingAgentOperations.text(
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, models.get(2), new Context(), new StreamOptions()))));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void sendsAdaptiveThinkingForCopilotOpus5() throws Exception {
		AtomicReference<JsonNode> request = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			assertBearer(exchange);
			request.set(Json.MAPPER.readTree(exchange.getRequestBody()));
			writeSse(exchange, """
					event: message_start
					data: {"message":{"id":"opus","usage":{"input_tokens":1}}}

					event: content_block_start
					data: {"index":0,"content_block":{"type":"text"}}

					event: content_block_delta
					data: {"index":0,"delta":{"type":"text_delta","text":"adaptive"}}

					event: message_stop
					data: {}

					""");
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Model opus = CodingAgentOperations.copyModel(
					CodingAgentOperations.requireCatalogModel(
					CodingAgentOperations.loadBundledModelCatalog(), GitHubCopilotAuth.PROVIDER_ID, "claude-opus-5"));
			opus.baseUrl = base;
			FileCredentialStore store = CodingAgentOperations.fileCredentialStore(Files.createTempDirectory("copilot-auth").resolve("auth.json"));
			CodingAgentOperations.modifyCredential(
					store,
					GitHubCopilotAuth.PROVIDER_ID,
					ignored -> CodingAgentOperations.oauthCredential("copilot-token", "github-token", Long.MAX_VALUE, null));
			GitHubCopilotProvider provider = CodingAgentOperations.newGitHubCopilotProvider(
					List.of(opus),
					CodingAgentOperations.gitHubCopilotAuth(store, URI.create(base), URI.create(base + "/token"), URI.create(base)));
			Context context = new Context();
			context.messages.add(CodingAgentOperations.userMessage("Use adaptive thinking"));

			assertEquals(
					"adaptive",
					CodingAgentOperations.text(CodingAgentOperations.result(
							CodingAgentOperations.stream(provider, opus, context, options(null, ThinkingLevel.MEDIUM)))));

			JsonNode payload = request.get();
			assertNotNull(payload);
			assertEquals("adaptive", payload.path("thinking").path("type").asText());
			assertEquals("summarized", payload.path("thinking").path("display").asText());
			assertFalse(payload.path("thinking").has("budget_tokens"));
			assertEquals("medium", payload.path("output_config").path("effort").asText());
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String id, String api, String baseUrl) {
		Model model = new Model();
		model.id = id;
		model.name = id;
		model.api = api;
		model.provider = GitHubCopilotAuth.PROVIDER_ID;
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		model.headers = new java.util.LinkedHashMap<>(java.util.Map.of("Copilot-Integration-Id", "vscode-chat"));
		return model;
	}

	private static void assertBearer(HttpExchange exchange) {
		assertEquals("Bearer copilot-token", exchange.getRequestHeaders().getFirst("Authorization"));
	}

	private static void writeSse(HttpExchange exchange, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
