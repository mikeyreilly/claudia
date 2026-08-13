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
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.UserMessage;

class OpenAiCompatibleProviderTest {
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
			OpenAiCompatibleProvider provider =
					new OpenAiCompatibleProvider("custom", "Custom", url(server), List.of(model));
			Context context = new Context("system instructions");
			context.messages.add(UserMessage.of("hello"));

			AssistantMessageEventStream stream =
					provider.stream(model, context, new StreamOptions().apiKey("test-key"));
			List<AssistantMessageEvent> events = new ArrayList<>();
			for (AssistantMessageEvent event : stream) {
				events.add(event);
			}

			AssistantMessage response = stream.result();
			assertEquals("hello world", response.text());
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
			OpenAiCompatibleProvider provider =
					new OpenAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessage result =
					provider.stream(model, new Context(), new StreamOptions().apiKey("test-key")).result();

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals("read", result.toolCalls().getFirst().name());
			assertEquals("README.md", result.toolCalls().getFirst().arguments().path("path").asText());
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
			OpenAiCompatibleProvider provider =
					new OpenAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessageEventStream stream =
					provider.stream(model, new Context(), new StreamOptions().apiKey("test-key"));
			List<AssistantMessageEvent> events = new ArrayList<>();
			for (AssistantMessageEvent event : stream) {
				events.add(event);
			}
			AssistantMessage result = stream.result();

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals(1, result.toolCalls().size());
			assertEquals("read", result.toolCalls().getFirst().name());
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
			OpenAiCompatibleProvider provider =
					new OpenAiCompatibleProvider("custom", "Custom", url(server), List.of(model));

			AssistantMessage result =
					provider.stream(model, new Context(), new StreamOptions().apiKey("test-key")).result();

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
			assertEquals("read", result.toolCalls().getFirst().name());
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String baseUrl) {
		return Model.builder()
				.id("test-model")
				.api("openai-completions")
				.provider("custom")
				.baseUrl(baseUrl)
				.cost(ModelCost.FREE)
				.contextWindow(1000)
				.maxTokens(100)
				.build();
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
