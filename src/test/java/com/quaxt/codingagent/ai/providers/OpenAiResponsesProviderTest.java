package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.FileCredentialStore;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserMessage;

class OpenAiResponsesProviderTest {
	@TempDir Path tempDir;

	@Test
	void usesSavedOpenAiApiKeyWhenNoRequestKeyIsSupplied() throws Exception {
		HttpServer server = server(exchange -> {
			assertEquals("Bearer saved-key", exchange.getRequestHeaders().getFirst("Authorization"));
			writeSse(
					exchange,
					"""
					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

					""");
		});
		try {
			FileCredentialStore credentials = new FileCredentialStore(tempDir.resolve("auth.json"));
			credentials.modify("openai", ignored -> new Credential.ApiKeyCredential("saved-key"));
			Model model = model(url(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(List.of(model), credentials);
			Context context = new Context();
			context.messages.add(UserMessage.of("hi"));

			AssistantMessage result = provider.stream(model, context, new StreamOptions()).result();

			assertEquals(StopReason.STOP, result.stopReason);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void streamsResponsesTextAndUsage() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeSse(
					exchange,
					"""
					data: {"type":"response.created","response":{"id":"resp_1"}}

					data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message"}}

					data: {"type":"response.output_text.delta","output_index":0,"delta":"hello "}

					data: {"type":"response.output_text.delta","output_index":0,"delta":"world"}

					data: {"type":"response.output_item.done","output_index":0,"item":{"id":"msg_1","type":"message"}}

					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7,"input_tokens_details":{"cached_tokens":1}}}}

					""");
		});
		try {
			Model model = model(url(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(List.of(model));
			Context context = new Context("system");
			context.messages.add(UserMessage.of("hi"));

			AssistantMessage result =
					provider.stream(model, context, new StreamOptions().apiKey("test-key")).result();

			assertEquals("hello world", result.text());
			assertEquals("resp_1", result.responseId);
			assertEquals(StopReason.STOP, result.stopReason);
			assertEquals(4, result.usage.input);
			assertEquals(1, result.usage.cacheRead);
			assertEquals(2, result.usage.output);
			assertTrue(request.get().contains("\"instructions\":\"system\""));
			assertTrue(request.get().contains("\"input_text\""));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void requestsAndStreamsReasoningSummaries() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeSse(
					exchange,
					"""
					data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning"}}

					data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Inspecting files"}

					data: {"type":"response.reasoning_summary_part.done","output_index":0}

					data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Checking tests"}

					data: {"type":"response.output_item.done","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Inspecting files"},{"type":"summary_text","text":"Checking tests"}]}}

					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Inspecting files"},{"type":"summary_text","text":"Checking tests"}],"encrypted_content":"opaque"}],"usage":{"input_tokens":3,"output_tokens":8,"total_tokens":11,"output_tokens_details":{"reasoning_tokens":6}}}}

					""");
		});
		try {
			Model model = reasoningModel(url(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(List.of(model));
			Context context = new Context();
			context.messages.add(UserMessage.of("inspect"));

			AssistantMessage result = provider.stream(
					model, context, new StreamOptions().apiKey("test-key").reasoning(ThinkingLevel.MEDIUM)).result();

			JsonNode body = Json.MAPPER.readTree(request.get());
			assertEquals("medium", body.path("reasoning").path("effort").asText());
			assertEquals("auto", body.path("reasoning").path("summary").asText());
			assertEquals("reasoning.encrypted_content", body.path("include").get(0).asText());
			assertFalse(body.path("store").asBoolean(true));
			assertEquals("Inspecting files\n\nChecking tests", result.thinking());
			assertTrue(((ThinkingContent) result.content.getFirst()).thinkingSignature().contains("opaque"));
			assertEquals(6, result.usage.reasoning);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void usesCodexRequestContractForChatGptResponses() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeSse(
					exchange,
					"""
					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

					""");
		});
		try {
			Model model = reasoningModel(url(server));
			OpenAiResponsesProvider provider = OpenAiResponsesProvider.codex("chatgpt", "ChatGPT", List.of(model));
			Context context = new Context();
			context.messages.add(UserMessage.of("inspect"));

			provider.stream(
					model,
					context,
					new StreamOptions()
							.apiKey("test-key")
							.reasoning(ThinkingLevel.MEDIUM)
							.sessionId("session-1")
							.maxTokens(4_096))
					.result();

			JsonNode body = Json.MAPPER.readTree(request.get());
			assertEquals("You are a helpful assistant.", body.path("instructions").asText());
			assertEquals("low", body.path("text").path("verbosity").asText());
			assertEquals("auto", body.path("tool_choice").asText());
			assertTrue(body.path("parallel_tool_calls").asBoolean());
			assertEquals("session-1", body.path("prompt_cache_key").asText());
			assertEquals("detailed", body.path("reasoning").path("summary").asText());
			assertFalse(body.has("max_output_tokens"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void serializesPriorToolCallsAsTopLevelResponsesItems() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = server(exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeSse(
					exchange,
					"""
					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

					""");
		});
		try {
			Model model = model(url(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(List.of(model));
			Context context = new Context();
			AssistantMessage assistant = new AssistantMessage(model.api, model.provider, model.id);
			assistant.content.add(new ThinkingContent(
					"inspected files",
					"{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"opaque\"}",
					false));
			assistant.content.add(new ToolCall("call_1", "list_files", Json.object().put("path", ".")));
			context.messages.add(assistant);
			context.messages.add(ToolResultMessage.text("call_1", "list_files", "file.txt", false));

			provider.stream(model, context, new StreamOptions().apiKey("test-key")).result();

			JsonNode input = Json.MAPPER.readTree(request.get()).path("input");
			assertEquals("reasoning", input.get(0).path("type").asText());
			assertEquals("opaque", input.get(0).path("encrypted_content").asText());
			assertEquals("function_call", input.get(1).path("type").asText());
			assertEquals("call_1", input.get(1).path("call_id").asText());
			assertEquals("function_call_output", input.get(2).path("type").asText());
			assertEquals("call_1", input.get(2).path("call_id").asText());
			assertTrue(!input.get(1).has("content"));
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String baseUrl) {
		return modelBuilder(baseUrl).build();
	}

	private static Model reasoningModel(String baseUrl) {
		return modelBuilder(baseUrl).reasoning(true).build();
	}

	private static Model.Builder modelBuilder(String baseUrl) {
		return Model.builder()
				.id("gpt-test")
				.api("openai-responses")
				.provider("openai")
				.baseUrl(baseUrl)
				.cost(ModelCost.FREE)
				.contextWindow(1000)
				.maxTokens(100);
	}

	private static HttpServer server(ExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/responses", exchange -> {
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
