package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.FileCredentialStore;
import com.quaxt.codingagent.ai.auth.GitHubCopilotAuth;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;

class GitHubCopilotProviderTest {
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
			FileCredentialStore store = new FileCredentialStore(Files.createTempDirectory("copilot-auth").resolve("auth.json"));
			store.modify(
					GitHubCopilotAuth.PROVIDER_ID,
					ignored -> new Credential.OAuthCredential("copilot-token", "github-token", Long.MAX_VALUE, null));
			GitHubCopilotProvider provider = new GitHubCopilotProvider(
					models,
					new GitHubCopilotAuth(store, URI.create(base), URI.create(base + "/token"), URI.create(base)));

			assertEquals("anthropic", provider.stream(models.get(0), new Context(), new StreamOptions()).result().text());
			assertEquals("completions", provider.stream(models.get(1), new Context(), new StreamOptions()).result().text());
			assertEquals("responses", provider.stream(models.get(2), new Context(), new StreamOptions()).result().text());
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String id, String api, String baseUrl) {
		return Model.builder()
				.id(id)
				.api(api)
				.provider(GitHubCopilotAuth.PROVIDER_ID)
				.baseUrl(baseUrl)
				.cost(ModelCost.FREE)
				.contextWindow(1000)
				.maxTokens(100)
				.headers(java.util.Map.of("Copilot-Integration-Id", "vscode-chat"))
				.build();
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
