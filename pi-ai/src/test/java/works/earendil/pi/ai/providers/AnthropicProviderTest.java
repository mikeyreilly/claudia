package works.earendil.pi.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import works.earendil.pi.ai.StreamOptions;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.Context;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.ModelCost;
import works.earendil.pi.ai.types.StopReason;
import works.earendil.pi.ai.types.UserMessage;

class AnthropicProviderTest {
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
			AnthropicProvider provider = new AnthropicProvider(List.of(model));
			Context context = new Context("be helpful");
			context.messages.add(UserMessage.of("hi"));

			AssistantMessage result =
					provider.stream(model, context, new StreamOptions().apiKey("test-key")).result();

			assertEquals("hello world", result.text());
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

	private static Model model(String baseUrl) {
		return Model.builder()
				.id("claude-test")
				.api("anthropic-messages")
				.provider("anthropic")
				.baseUrl(baseUrl)
				.cost(ModelCost.FREE)
				.contextWindow(200_000)
				.maxTokens(8_000)
				.build();
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
