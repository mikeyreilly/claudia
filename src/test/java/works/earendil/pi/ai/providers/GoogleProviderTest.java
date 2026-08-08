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

class GoogleProviderTest {
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
			GoogleProvider provider = new GoogleProvider(List.of(model));
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
			assertTrue(request.get().contains("\"system\""));
			assertTrue(request.get().contains("\"contents\""));
		} finally {
			server.stop(0);
		}
	}

	private static Model model(String baseUrl) {
		return Model.builder()
				.id("gemini-test")
				.api("google-generative-ai")
				.provider("google")
				.baseUrl(baseUrl)
				.cost(ModelCost.FREE)
				.contextWindow(1000)
				.maxTokens(100)
				.build();
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
