package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

class AnthropicProviderTest {
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
	void sendsMessagesRequestAndStreamsTextAndUsage() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			assertEquals("test-key", exchange.getRequestHeaders().getFirst("x-api-key"));
			assertEquals("2023-06-01", exchange.getRequestHeaders().getFirst("anthropic-version"));
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeSse(
					exchange,
					"""
					event: message_start
					data: {"message":{"id":"msg_1","model":"claude-test","usage":{"input_tokens":10,"cache_read_input_tokens":3}}}

					event: content_block_start
					data: {"index":0,"content_block":{"type":"text"}}

					event: content_block_delta
					data: {"index":0,"delta":{"type":"text_delta","text":"hello "}}

					event: content_block_delta
					data: {"index":0,"delta":{"type":"text_delta","text":"world"}}

					event: content_block_stop
					data: {"index":0}

					event: message_delta
					data: {"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

					event: message_stop
					data: {}

					""");
		});
		try {
			Model model = model(url(server));
			AnthropicProvider provider = CodingAgentOperations.anthropicProvider(List.of(model));
			Context context = new Context("be helpful");
			context.messages.add(CodingAgentOperations.userMessage("hi"));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, model, context, options("test-key")));

			assertEquals("hello world", CodingAgentOperations.text(result));
			assertEquals("msg_1", result.responseId);
			assertEquals(StopReason.STOP, result.stopReason);
			assertEquals(10, result.usage.input);
			assertEquals(3, result.usage.cacheRead);
			assertEquals(2, result.usage.output);
			assertTrue(request.get().contains("\"system\":\"be helpful\""));
			assertTrue(request.get().contains("\"model\":\"claude-test\""));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void capturesAndReplaysThinkingSignatureDeltasAcrossToolTurns() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<JsonNode> followUpRequest = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
			switch (requests.getAndIncrement()) {
				case 0 -> writeSse(exchange, toolUseResponseWithThinkingSignature());
				case 1 -> {
					followUpRequest.set(request);
					writeSse(exchange, finalTextResponse("finished"));
				}
				default -> throw new AssertionError("Unexpected Anthropic request");
			}
		});
		try {
			Model model = CodingAgentOperations.copyModel(model(url(server)));
			model.reasoning = true;
			AnthropicProvider provider = CodingAgentOperations.anthropicProvider(List.of(model));
			Context initial = new Context();
			initial.messages.add(CodingAgentOperations.userMessage("Inspect the file"));

			AssistantMessage toolUse = CodingAgentOperations.result(CodingAgentOperations.stream(provider,
					model, initial, options("test-key", ThinkingLevel.MEDIUM)));
			assertEquals(StopReason.TOOL_USE, toolUse.stopReason);
			ThinkingContent thinking = assertInstanceOf(ThinkingContent.class, toolUse.content.getFirst());
			assertEquals("checking", thinking.thinking);
			assertEquals("opaque-signature", thinking.thinkingSignature);

			Context followUp = CodingAgentOperations.copy(initial);
			followUp.messages.add(toolUse);
			followUp.messages.add(CodingAgentOperations.toolResultMessage("toolu_1", "read", "file contents", false));
			AssistantMessage completed = CodingAgentOperations.result(CodingAgentOperations.stream(provider,
					model, followUp, options("test-key", ThinkingLevel.MEDIUM)));
			assertEquals("finished", CodingAgentOperations.text(completed));

			JsonNode followUpPayload = followUpRequest.get();
			assertNotNull(followUpPayload);
			JsonNode replayed = followUpPayload.path("messages").get(1).path("content").get(0);
			assertEquals("thinking", replayed.path("type").asText());
			assertEquals("checking", replayed.path("thinking").asText());
			assertEquals("opaque-signature", replayed.path("signature").asText());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void convertsUnsignedThinkingToTextInsteadOfReplayingAnEmptySignature() throws Exception {
		AtomicReference<JsonNode> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			request.set(Json.MAPPER.readTree(exchange.getRequestBody()));
			writeSse(exchange, finalTextResponse("finished"));
		});
		try {
			Model model = model(url(server));
			AnthropicProvider provider = CodingAgentOperations.anthropicProvider(List.of(model));
			AssistantMessage prior = new AssistantMessage(model.api, model.provider, model.id);
			prior.content.add(CodingAgentOperations.thinkingContent("interrupted reasoning", "", false));
			Context context = new Context();
			context.messages.add(CodingAgentOperations.userMessage("First request"));
			context.messages.add(prior);
			context.messages.add(CodingAgentOperations.userMessage("Continue"));

			assertEquals(
					"finished",
					CodingAgentOperations.text(CodingAgentOperations.result(
							CodingAgentOperations.stream(provider, model, context, options("test-key")))));

			JsonNode payload = request.get();
			assertNotNull(payload);
			JsonNode replayed = payload.path("messages").get(1).path("content").get(0);
			assertEquals("text", replayed.path("type").asText());
			assertEquals("interrupted reasoning", replayed.path("text").asText());
			assertFalse(replayed.has("signature"));
		} finally {
			server.stop(0);
		}
	}

	private static String toolUseResponseWithThinkingSignature() {
		return """
			event: message_start
			data: {"message":{"id":"msg_tool","usage":{"input_tokens":1}}}

			event: content_block_start
			data: {"index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

			event: content_block_delta
			data: {"index":0,"delta":{"type":"thinking_delta","thinking":"checking"}}

			event: content_block_delta
			data: {"index":0,"delta":{"type":"signature_delta","signature":"opaque-"}}

			event: content_block_delta
			data: {"index":0,"delta":{"type":"signature_delta","signature":"signature"}}

			event: content_block_stop
			data: {"index":0}

			event: content_block_start
			data: {"index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{}}}

			event: content_block_delta
			data: {"index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}

			event: content_block_stop
			data: {"index":1}

			event: message_delta
			data: {"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}

			event: message_stop
			data: {}

			""";
	}

	private static String finalTextResponse(String text) {
		return """
			event: message_start
			data: {"message":{"id":"msg_done","usage":{"input_tokens":1}}}

			event: content_block_start
			data: {"index":0,"content_block":{"type":"text"}}

			event: content_block_delta
			data: {"index":0,"delta":{"type":"text_delta","text":"%s"}}

			event: content_block_stop
			data: {"index":0}

			event: message_delta
			data: {"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

			event: message_stop
			data: {}

			""".formatted(text);
	}

	private static Model model(String baseUrl) {
		Model model = new Model();
		model.id = "claude-test";
		model.name = "claude-test";
		model.api = "anthropic-messages";
		model.provider = "anthropic";
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 200_000;
		model.maxTokens = 8_000;
		return model;
	}

	private static HttpServer server(ExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
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
		return "http://127.0.0.1:" + server.getAddress().getPort();
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
