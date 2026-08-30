package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;

class OpenAiCompatibleProviderTest {
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
	void sendsChatCompletionRequestAndStreamsText() throws Exception {
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
			writeSse(
					exchange,
					"""
					data: {"choices":[{"delta":{"content":"hello "}}]}

					data: {"choices":[{"delta":{"content":"world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

					data: [DONE]

					""");
		});
		try {
			Model model = model(url(server));
			CodingAgentOperations provider =
					CodingAgentOperations.openAiCompatibleProvider("custom", "Custom", url(server), List.of(model));
			Context context = new Context("system instructions");
			context.messages.add(CodingAgentOperations.userMessage("hello"));

			AssistantMessageEventStream stream =
					CodingAgentOperations.stream(provider, model, context, options("test-key"));
			List<AssistantMessageEvent> events = new ArrayList<>();
			for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
				events.add(event);
			}

			AssistantMessage response = CodingAgentOperations.result(stream);
			assertEquals("hello world", CodingAgentOperations.text(response));
			assertEquals(StopReason.STOP, response.stopReason);
			assertEquals(4, response.usage.input);
			assertEquals(2, response.usage.output);
			assertTrue(requestBody.get().contains("\"stream\":true"));
			assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
			assertTrue(requestBody.get().contains("\"system instructions\""));
			assertInstanceOf(AssistantMessageEvent.Start.class, events.getFirst());
			assertInstanceOf(AssistantMessageEvent.TextStart.class, events.get(1));
			assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(2));
			assertInstanceOf(AssistantMessageEvent.Done.class, events.getLast());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void assemblesStreamedToolCallArguments() throws Exception {
		HttpServer server = server(exchange -> writeSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":"}}]}}]}

				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"README.md\\"}"}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = model(url(server));
			CodingAgentOperations provider =
					CodingAgentOperations.openAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, model, new Context(), options("test-key")));

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals("read", CodingAgentOperations.toolCalls(result).getFirst().name);
			assertEquals("README.md", CodingAgentOperations.toolCalls(result).getFirst().arguments.path("path").asText());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void treatsBlankToolArgumentsAsAnEmptyObject() throws Exception {
		HttpServer server = server(exchange -> writeSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"toolu_1","function":{"name":"parameterless_tool","arguments":""}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = model(url(server));
			CodingAgentOperations provider =
					CodingAgentOperations.openAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, model, new Context(), options("test-key")));

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals(1, CodingAgentOperations.toolCalls(result).size());
			assertEquals("toolu_1", CodingAgentOperations.toolCalls(result).getFirst().id);
			assertEquals("parameterless_tool", CodingAgentOperations.toolCalls(result).getFirst().name);
			assertTrue(CodingAgentOperations.toolCalls(result).getFirst().arguments.isEmpty());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void ignoresCompletelyEmptyToolCallAfterAValidCall() throws Exception {
		HttpServer server = server(exchange -> writeSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":\\"README.md\\"}"}},{"index":1,"function":{"name":"","arguments":""}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = model(url(server));
			CodingAgentOperations provider =
					CodingAgentOperations.openAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessageEventStream stream =
					CodingAgentOperations.stream(provider, model, new Context(), options("test-key"));
			List<AssistantMessageEvent> events = new ArrayList<>();
			for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
				events.add(event);
			}
			AssistantMessage result = CodingAgentOperations.result(stream);

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals(1, CodingAgentOperations.toolCalls(result).size());
			assertEquals("read", CodingAgentOperations.toolCalls(result).getFirst().name);
			assertEquals(
					1L,
					events.stream().filter(AssistantMessageEvent.ToolCallStart.class::isInstance).count());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsArrayToolArgumentsAndPreservesRawStreamFragments() throws Exception {
		HttpServer server = server(exchange -> writeSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":\\"README.md\\"}"}},{"index":1,"id":"call_2","function":{"name":"broken_","arguments":"["}}]}}]}

				data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"tool","arguments":"]"}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = model(url(server));
			CodingAgentOperations provider =
					CodingAgentOperations.openAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.stream(provider, model, new Context(), options("test-key")));

			assertEquals(StopReason.ERROR, result.stopReason);
			assertTrue(result.errorMessage.startsWith("OpenAI tool call arguments must be a JSON object"));
			assertTrue(result.errorMessage.contains("\"index\":1"));
			assertTrue(result.errorMessage.contains("\"id\":\"call_2\""));
			assertTrue(result.errorMessage.contains("\"name\":\"broken_tool\""));
			assertTrue(result.errorMessage.contains("\"arguments\":\"[]\""));
			assertTrue(result.errorMessage.contains("\"name\":\"broken_\""));
			assertTrue(result.errorMessage.contains("\"arguments\":\"[\""));
			assertTrue(result.errorMessage.contains("\"name\":\"tool\""));
			assertTrue(result.errorMessage.contains("\"arguments\":\"]\""));
			assertEquals("read", CodingAgentOperations.toolCalls(result).getFirst().name);
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String baseUrl) {
		Model model = new Model();
		model.id = "test-model";
		model.name = "test-model";
		model.api = "openai-completions";
		model.provider = "custom";
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		return model;
	}

	private static HttpServer server(ExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/completions", exchange -> {
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
