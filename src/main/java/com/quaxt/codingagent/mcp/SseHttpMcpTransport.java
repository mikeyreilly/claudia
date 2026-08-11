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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** Compatibility transport for the MCP 2024-11-05 HTTP+SSE protocol. */
final class SseHttpMcpTransport implements McpTransport {
	private final URI url;
	private final Map<String, String> headers;
	private final McpOAuthClient.Session oauth;
	private final java.nio.file.Path workspace;
	private final HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	private final AtomicLong nextId = new AtomicLong(1);
	private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final CompletableFuture<URI> endpoint = new CompletableFuture<>();
	private volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> {};
	private volatile CompletableFuture<HttpResponse<InputStream>> opening;
	private volatile InputStream eventStream;
	private volatile String protocolVersion;
	private volatile String sessionId;
	private volatile boolean closed;

	SseHttpMcpTransport(
			McpServerConfig.Remote config, java.nio.file.Path workspace, McpOAuthClient.Session oauth) {
		url = config.url();
		headers = config.headers();
		this.oauth = oauth;
		this.workspace = workspace.toAbsolutePath().normalize();
		openEventStream(true);
	}

	private void openEventStream(boolean authRetry) {
		String bearer;
		try {
			bearer = oauth == null ? null : oauth.accessToken();
		} catch (Exception error) {
			endpoint.completeExceptionally(error);
			return;
		}
		HttpRequest request = request(url, Duration.ofSeconds(30), bearer)
				.setHeader("Accept", "text/event-stream")
				.GET()
				.build();
		opening = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		opening.whenComplete((response, error) -> {
			if (closed) {
				if (response != null) {
					try {
						response.body().close();
					} catch (IOException ignored) {}
				}
				return;
			}
			if (error != null) {
				endpoint.completeExceptionally(error);
				failPending(new IOException("Failed to open MCP SSE stream", error));
				return;
			}
			captureSession(response);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String body = readErrorBody(response.body());
				McpHttpException failure = httpError(response.statusCode(), response.uri(), response.headers().map(), body, url);
				try {
					if (authRetry && oauth != null && oauth.refreshAfterUnauthorized(failure, bearer)) {
						openEventStream(false);
						return;
					}
				} catch (Exception refreshError) {
					endpoint.completeExceptionally(refreshError);
					failPending(refreshError);
					return;
				}
				endpoint.completeExceptionally(failure);
				failPending(failure);
				return;
			}
			eventStream = response.body();
			Thread.ofVirtual().name("mcp-sse-reader").start(() -> readEvents(response.body()));
		});
	}

	@Override
	public JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) throws Exception {
		URI target = await(endpoint, timeout, signal);
		long id = nextId.getAndIncrement();
		CompletableFuture<JsonNode> result = new CompletableFuture<>();
		pending.put(id, result);
		ObjectNode envelope = Json.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) envelope.set("params", params);
		try {
			post(target, envelope, timeout, signal);
			return await(result, timeout, signal);
		} catch (TimeoutException | InterruptedException error) {
			try {
				notify("notifications/cancelled", Json.object().put("requestId", id).put("reason", error.getMessage()));
			} catch (Exception ignored) {}
			throw error;
		} finally {
			pending.remove(id);
		}
	}

	@Override
	public void notify(String method, ObjectNode params) throws Exception {
		URI target = await(endpoint, Duration.ofSeconds(10), null);
		ObjectNode envelope = Json.object().put("jsonrpc", "2.0").put("method", method);
		if (params != null) envelope.set("params", params);
		post(target, envelope, Duration.ofSeconds(10), null);
	}

	@Override
	public void protocolVersion(String version) {
		protocolVersion = version;
	}

	@Override
	public void onNotification(BiConsumer<String, JsonNode> listener) {
		notificationListener = listener == null ? (method, params) -> {} : listener;
	}

	private void post(URI target, JsonNode message, Duration timeout, AbortSignal signal) throws Exception {
		post(target, message, timeout, signal, true);
	}

	private void post(
			URI target, JsonNode message, Duration timeout, AbortSignal signal, boolean authRetry) throws Exception {
		String bearer = oauth == null ? null : oauth.accessToken();
		HttpRequest request = request(target, timeout, bearer)
				.setHeader("Content-Type", "application/json")
				.setHeader("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(message), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = await(
				client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), timeout, signal);
		captureSession(response);
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			McpHttpException failure = httpError(
					response.statusCode(), response.uri(), response.headers().map(), response.body(), target);
			if (authRetry && oauth != null && oauth.refreshAfterUnauthorized(failure, bearer)) {
				post(target, message, timeout, signal, false);
				return;
			}
			throw failure;
		}
		if (response.body() != null && !response.body().isBlank()) {
			JsonNode direct = Json.MAPPER.readTree(response.body());
			dispatch(direct);
		}
	}

	private void readEvents(InputStream stream) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String event = "message";
			StringBuilder data = new StringBuilder();
			String line;
			while (!closed && (line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					dispatchEvent(event, data.toString());
					event = "message";
					data.setLength(0);
				} else if (line.startsWith("event:")) {
					event = line.substring(6).strip();
				} else if (line.startsWith("data:")) {
					if (!data.isEmpty()) data.append('\n');
					data.append(line.substring(5).stripLeading());
				}
			}
			if (!data.isEmpty()) dispatchEvent(event, data.toString());
			if (!closed) failPending(new IOException("MCP SSE event stream closed"));
		} catch (Exception error) {
			if (!closed) {
				endpoint.completeExceptionally(error);
				failPending(error instanceof Exception exception ? exception : new IOException(String.valueOf(error)));
			}
		}
	}

	private void dispatchEvent(String event, String data) throws IOException {
		if (data.isBlank()) return;
		if (event.equals("endpoint")) {
			try {
				endpoint.complete(url.resolve(data.strip()));
			} catch (IllegalArgumentException error) {
				endpoint.completeExceptionally(new IOException("Invalid MCP SSE message endpoint: " + data, error));
			}
			return;
		}
		dispatch(Json.MAPPER.readTree(data));
	}

	private void dispatch(JsonNode message) {
		if (message == null) return;
		if (message.isArray()) {
			message.forEach(this::dispatch);
			return;
		}
		if (!message.isObject()) return;
		JsonNode methodNode = message.get("method");
		JsonNode id = message.get("id");
		if (methodNode != null && methodNode.isTextual()) {
			String method = methodNode.asText();
			JsonNode params = message.get("params");
			if (id == null || id.isNull()) notificationListener.accept(method, params);
			else respondToServerRequest(id, method);
			return;
		}
		if (id == null || !id.canConvertToLong()) return;
		CompletableFuture<JsonNode> result = pending.get(id.asLong());
		if (result == null) return;
		JsonNode error = message.get("error");
		if (error != null && !error.isNull()) result.completeExceptionally(rpcError(error));
		else result.complete(message.get("result"));
	}

	private void respondToServerRequest(JsonNode id, String method) {
		ObjectNode response = Json.object().put("jsonrpc", "2.0");
		response.set("id", id);
		if (method.equals("ping")) response.set("result", Json.object());
		else if (method.equals("roots/list")) {
			ObjectNode result = Json.object();
			result.putArray("roots")
					.addObject()
					.put("uri", workspace.toUri().toString())
					.put("name", workspace.getFileName() == null ? workspace.toString() : workspace.getFileName().toString());
			response.set("result", result);
		} else response.set(
				"error", Json.object().put("code", -32601).put("message", "Client does not support " + method));
		endpoint.thenAccept(target -> Thread.ofVirtual().start(() -> {
			try {
				post(target, response, Duration.ofSeconds(10), null);
			} catch (Exception ignored) {}
		}));
	}

	private HttpRequest.Builder request(URI target, Duration timeout, String bearer) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout);
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
				throw new InterruptedException("MCP request cancelled");
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw new TimeoutException("MCP request timed out after " + timeout.toMillis() + "ms");
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

	private void failPending(Exception error) {
		for (CompletableFuture<JsonNode> future : pending.values()) future.completeExceptionally(error);
		pending.clear();
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		if (opening != null) opening.cancel(true);
		failPending(new IOException("MCP SSE transport closed"));
		endpoint.completeExceptionally(new IOException("MCP SSE transport closed"));
		InputStream stream = eventStream;
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ignored) {}
		}
	}

	private static IOException rpcError(JsonNode error) {
		String message = error.path("message").asText("MCP JSON-RPC error");
		if (error.has("code")) message += " (" + error.path("code").asText() + ")";
		return new IOException(message);
	}

	private static String readErrorBody(InputStream stream) {
		try (stream) {
			byte[] bytes = stream.readNBytes(4_001);
			String value = new String(bytes, 0, Math.min(bytes.length, 4_000), StandardCharsets.UTF_8);
			return bytes.length > 4_000 ? value + "..." : value;
		} catch (IOException ignored) {
			return "";
		}
	}

	private static McpHttpException httpError(
			int status, URI responseUri, Map<String, java.util.List<String>> headers, String body, URI target) {
		String message = switch (status) {
			case 401 -> "MCP server requires authentication";
			case 403 -> "MCP server rejected the configured credentials";
			default -> "MCP SSE endpoint returned HTTP " + status;
		};
		if (body != null && !body.isBlank()) message += ": " + abbreviate(body);
		return new McpHttpException(status, responseUri, headers, body, message + " (" + target + ")");
	}

	private static String abbreviate(String value) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
	}

	private static boolean restricted(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "content-length", "host", "connection", "upgrade" -> true;
			default -> false;
		};
	}
}
