package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** MCP Streamable HTTP transport (protocol version 2025-03-26 and newer). */
final class StreamableHttpMcpTransport implements McpTransport {
	private final URI url;
	private final Map<String, String> headers;
	private final McpOAuthClient.Session oauth;
	private final PathRoot root;
	private final HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	private final AtomicLong nextId = new AtomicLong(1);
	private volatile String sessionId;
	private volatile String protocolVersion;
	private volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> {};
	private volatile InputStream listenerStream;
	private volatile CompletableFuture<?> listenerRequest;
	private volatile boolean closed;

	StreamableHttpMcpTransport(
			McpServerConfig.Remote config, java.nio.file.Path workspace, McpOAuthClient.Session oauth) {
		url = config.url();
		headers = config.headers();
		this.oauth = oauth;
		root = new PathRoot(workspace.toAbsolutePath().normalize());
	}

	@Override
	public JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) throws Exception {
		long id = nextId.getAndIncrement();
		ObjectNode envelope = Json.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) envelope.set("params", params);
		HttpResponse<String> response = post(envelope, timeout, signal);
		List<JsonNode> messages = responseMessages(response);
		for (JsonNode message : messages) {
			if (isId(message.get("id"), id) && message.get("method") == null) {
				JsonNode error = message.get("error");
				if (error != null && !error.isNull()) throw rpcError(error);
				return message.get("result");
			}
			dispatchServerMessage(message, timeout);
		}
		throw new IOException("MCP HTTP response did not contain JSON-RPC result for " + method);
	}

	@Override
	public void notify(String method, ObjectNode params) throws Exception {
		ObjectNode envelope = Json.object().put("jsonrpc", "2.0").put("method", method);
		if (params != null) envelope.set("params", params);
		HttpResponse<String> response = post(envelope, Duration.ofSeconds(10), null);
		for (JsonNode message : responseMessages(response)) dispatchServerMessage(message, Duration.ofSeconds(10));
		if (method.equals("notifications/initialized") && response.statusCode() == 202) startListener();
	}

	@Override
	public void protocolVersion(String version) {
		protocolVersion = version;
	}

	@Override
	public void onNotification(BiConsumer<String, JsonNode> listener) {
		notificationListener = listener == null ? (method, params) -> {} : listener;
	}

	private HttpResponse<String> post(JsonNode message, Duration timeout, AbortSignal signal) throws Exception {
		return post(message, timeout, signal, true);
	}

	private HttpResponse<String> post(JsonNode message, Duration timeout, AbortSignal signal, boolean authRetry)
			throws Exception {
		if (closed) throw new IOException("MCP HTTP transport is closed");
		String bearer = oauth == null ? null : oauth.accessToken();
		HttpRequest.Builder request = request(url, timeout, bearer)
				.setHeader("Content-Type", "application/json")
				.setHeader("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(message), StandardCharsets.UTF_8));
		HttpResponse<String> response = await(
				client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
				timeout,
				signal);
		captureSession(response);
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			McpHttpException failure = httpError(response);
			if (authRetry && oauth != null && oauth.refreshAfterUnauthorized(failure, bearer)) {
				return post(message, timeout, signal, false);
			}
			throw failure;
		}
		return response;
	}

	private List<JsonNode> responseMessages(HttpResponse<String> response) throws IOException {
		String body = response.body();
		if (body == null || body.isBlank() || response.statusCode() == 202) return List.of();
		String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
		if (contentType.contains("text/event-stream") || body.stripLeading().startsWith("event:")) {
			return parseSse(body);
		}
		JsonNode parsed;
		try {
			parsed = Json.MAPPER.readTree(body);
		} catch (IOException error) {
			throw new IOException("Invalid JSON response from MCP server at " + url + ": " + abbreviate(body), error);
		}
		if (parsed.isArray()) {
			List<JsonNode> result = new ArrayList<>();
			parsed.forEach(result::add);
			return result;
		}
		return List.of(parsed);
	}

	private List<JsonNode> parseSse(String body) throws IOException {
		List<JsonNode> result = new ArrayList<>();
		StringBuilder data = new StringBuilder();
		for (String line : body.split("\\R", -1)) {
			if (line.isEmpty()) {
				addSseData(result, data);
				continue;
			}
			if (line.startsWith("data:")) {
				if (!data.isEmpty()) data.append('\n');
				data.append(line.substring(5).stripLeading());
			}
		}
		addSseData(result, data);
		return result;
	}

	private static void addSseData(List<JsonNode> result, StringBuilder data) throws IOException {
		if (data.isEmpty()) return;
		JsonNode parsed = Json.MAPPER.readTree(data.toString());
		if (parsed.isArray()) parsed.forEach(result::add);
		else result.add(parsed);
		data.setLength(0);
	}

	private void dispatchServerMessage(JsonNode message, Duration timeout) {
		if (!message.isObject() || !message.path("method").isTextual()) return;
		String method = message.path("method").asText();
		JsonNode id = message.get("id");
		JsonNode params = message.get("params");
		if (id == null || id.isNull()) {
			notificationListener.accept(method, params);
			return;
		}
		ObjectNode response = Json.object().put("jsonrpc", "2.0");
		response.set("id", id);
		if (method.equals("ping")) response.set("result", Json.object());
		else if (method.equals("roots/list")) response.set("result", root.result());
		else response.set(
				"error", Json.object().put("code", -32601).put("message", "Client does not support " + method));
		try {
			post(response, timeout, null);
		} catch (Exception ignored) {
			// The original request will report a transport failure if one occurs.
		}
	}

	private void startListener() {
		if (closed || listenerRequest != null) return;
		HttpRequest request;
		try {
			String bearer = oauth == null ? null : oauth.accessToken();
			request = request(url, null, bearer).setHeader("Accept", "text/event-stream").GET().build();
		} catch (Exception ignored) {
			return;
		}
		CompletableFuture<HttpResponse<InputStream>> future =
				client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		listenerRequest = future;
		future.whenComplete((response, error) -> {
			if (closed || error != null || response == null) return;
			if (response.statusCode() == 405) {
				try {
					response.body().close();
				} catch (IOException ignored) {}
				return;
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				try {
					response.body().close();
				} catch (IOException ignored) {}
				return;
			}
			listenerStream = response.body();
			Thread.ofVirtual().name("mcp-http-listener").start(() -> readListener(response.body()));
		});
	}

	private void readListener(InputStream stream) {
		try (BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while (!closed && (line = input.readLine()) != null) {
				if (line.isEmpty()) {
					dispatchListenerData(data);
				} else if (line.startsWith("data:")) {
					if (!data.isEmpty()) data.append('\n');
					data.append(line.substring(5).stripLeading());
				}
			}
			dispatchListenerData(data);
		} catch (IOException ignored) {
			// The optional GET stream may be unavailable or close at any time.
		}
	}

	private void dispatchListenerData(StringBuilder data) {
		if (data.isEmpty()) return;
		try {
			JsonNode message = Json.MAPPER.readTree(data.toString());
			if (message.isArray()) message.forEach(item -> dispatchServerMessage(item, Duration.ofSeconds(10)));
			else dispatchServerMessage(message, Duration.ofSeconds(10));
		} catch (IOException ignored) {
			// Ignore malformed optional server events; request responses still use POST.
		} finally {
			data.setLength(0);
		}
	}

	private HttpRequest.Builder request(URI target, Duration timeout, String bearer) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(target);
		if (timeout != null) builder.timeout(timeout);
		for (var header : headers.entrySet()) {
			if (!restricted(header.getKey())) builder.header(header.getKey(), header.getValue());
		}
		if (bearer != null) builder.setHeader("Authorization", "Bearer " + bearer);
		if (sessionId != null) builder.setHeader("Mcp-Session-Id", sessionId);
		if (protocolVersion != null) builder.setHeader("MCP-Protocol-Version", protocolVersion);
		return builder;
	}

	private void captureSession(HttpResponse<?> response) {
		response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
	}

	private static <T> T await(CompletableFuture<T> future, Duration timeout, AbortSignal signal) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			if ((signal != null && signal.isAborted()) || Thread.currentThread().isInterrupted()) {
				future.cancel(true);
				throw new InterruptedException("MCP HTTP request cancelled");
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				future.cancel(true);
				throw new TimeoutException("MCP HTTP request timed out after " + timeout.toMillis() + "ms");
			}
			try {
				return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
			} catch (TimeoutException ignored) {
				// Poll cancellation.
			} catch (InterruptedException error) {
				future.cancel(true);
				Thread.currentThread().interrupt();
				throw error;
			} catch (ExecutionException error) {
				Throwable cause = error.getCause();
				if (cause instanceof Exception exception) throw exception;
				throw new IOException(String.valueOf(cause), cause);
			}
		}
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		if (listenerRequest != null) listenerRequest.cancel(true);
		if (listenerStream != null) {
			try {
				listenerStream.close();
			} catch (IOException ignored) {}
		}
		if (sessionId == null) return;
		try {
			String bearer = oauth == null ? null : oauth.cachedAccessToken();
			HttpRequest request = request(url, Duration.ofSeconds(2), bearer).DELETE().build();
			client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
		} catch (Exception ignored) {
			// Session deletion is best effort.
		}
	}

	private static boolean isId(JsonNode value, long id) {
		return value != null && value.canConvertToLong() && value.asLong() == id;
	}

	private static IOException rpcError(JsonNode error) {
		String message = error.path("message").asText("MCP JSON-RPC error");
		if (error.has("code")) message += " (" + error.path("code").asText() + ")";
		if (error.has("data")) message += ": " + error.path("data");
		return new IOException(message);
	}

	private McpHttpException httpError(HttpResponse<String> response) {
		int status = response.statusCode();
		String body = response.body();
		String message = switch (status) {
			case 401 -> "MCP server requires authentication";
			case 403 -> "MCP server rejected the configured credentials";
			case 404 -> "MCP endpoint was not found";
			case 405 -> "MCP endpoint does not support Streamable HTTP";
			default -> "MCP server returned HTTP " + status;
		};
		if (body != null && !body.isBlank()) message += ": " + abbreviate(body);
		return new McpHttpException(
				status, response.uri(), response.headers().map(), body, message + " (" + url + ")");
	}

	private static boolean restricted(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "content-length", "host", "connection", "upgrade" -> true;
			default -> false;
		};
	}

	private static String abbreviate(String value) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
	}

	private record PathRoot(java.nio.file.Path path) {
		ObjectNode result() {
			ObjectNode result = Json.object();
			result.putArray("roots")
					.addObject()
					.put("uri", path.toUri().toString())
					.put("name", path.getFileName() == null ? path.toString() : path.getFileName().toString());
			return result;
		}
	}
}
