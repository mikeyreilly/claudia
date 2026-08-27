package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;

class GoogleProviderTest {
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
	void sendsGeminiRequestAndStreamsTextAndUsage() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			assertTrue(exchange.getRequestURI().getQuery().contains("key=test-key"));
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeSse(
					exchange,
					"""
					data: {"responseId":"resp_1","candidates":[{"content":{"parts":[{"text":"hello "}]}}]}

					data: {"responseId":"resp_1","candidates":[{"content":{"parts":[{"text":"world"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"cachedContentTokenCount":1,"candidatesTokenCount":2,"totalTokenCount":7}}

					""");
		});
		try {
			Model model = model(url(server));
			GoogleProvider provider = CodingAgentOperations.googleProvider(List.of(model));
			Context context = new Context("system");
			context.messages.add(CodingAgentOperations.userMessage("hi"));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, model, context, options("test-key")));

			assertEquals("hello world", CodingAgentOperations.text(result));
			assertEquals("resp_1", result.responseId);
			assertEquals(StopReason.STOP, result.stopReason);
			assertEquals(4, result.usage.input);
			assertEquals(1, result.usage.cacheRead);
			assertEquals(2, result.usage.output);
			assertTrue(request.get().contains("\"system\""));
			assertTrue(request.get().contains("\"contents\""));
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String baseUrl) {
		Model model = new Model();
		model.id = "gemini-test";
		model.name = "gemini-test";
		model.api = "google-generative-ai";
		model.provider = "google";
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		return model;
	}

	private static HttpServer server(ExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1beta/models/gemini-test:streamGenerateContent", exchange -> {
			try {
				handler.handle(exchange);
			} catch (Exception e) {
				throw new java.io.IOException(e);
			} finally {
				exchange.close();
			}
		});
		server.start();
		return server;
	}

	private static String url(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
	}

	private static void writeSse(HttpExchange exchange, String body) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	@FunctionalInterface
	private interface ExchangeHandler {
		void handle(HttpExchange exchange) throws Exception;
	}
}
