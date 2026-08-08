package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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

/** MCP's newline-delimited JSON-RPC stdio transport. */
final class StdioMcpTransport implements McpTransport {
	private static final int STDERR_LIMIT = 16 * 1024;

	private final Process process;
	private final BufferedWriter writer;
	private final Object writeLock = new Object();
	private final AtomicLong nextId = new AtomicLong(1);
	private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final Path workspace;
	private final StringBuilder stderr = new StringBuilder();
	private volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> {};
	private volatile boolean closed;

	StdioMcpTransport(McpServerConfig.Local config, Path workspace) throws IOException {
		this.workspace = workspace.toAbsolutePath().normalize();
		Path processDirectory = config.cwd() == null || config.cwd().isBlank()
				? this.workspace
				: resolve(this.workspace, config.cwd());
		ProcessBuilder builder = new ProcessBuilder(config.command());
		builder.directory(processDirectory.toFile());
		builder.environment().putAll(config.environment());
		process = builder.start();
		writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Thread.ofVirtual().name("mcp-stdio-reader").start(this::readMessages);
		Thread.ofVirtual().name("mcp-stderr-reader").start(this::readStderr);
		process.onExit().thenRun(() -> failPending(new IOException(exitMessage())));
	}

	@Override
	public JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) throws Exception {
		long id = nextId.getAndIncrement();
		CompletableFuture<JsonNode> response = new CompletableFuture<>();
		pending.put(id, response);
		ObjectNode message = Json.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) message.set("params", params);
		try {
			write(message);
		} catch (Exception error) {
			pending.remove(id);
			throw error;
		}

		long deadline = System.nanoTime() + timeout.toNanos();
		try {
			while (true) {
				if ((signal != null && signal.isAborted()) || Thread.currentThread().isInterrupted()) {
					cancel(id, "Request cancelled");
					throw new InterruptedException("MCP request cancelled");
				}
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					cancel(id, "Request timed out");
					throw new TimeoutException("MCP request " + method + " timed out after " + timeout.toMillis() + "ms");
				}
				try {
					return response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
				} catch (TimeoutException ignored) {
					// Poll so the agent's cooperative abort signal is observed promptly.
				} catch (InterruptedException error) {
					cancel(id, "Request cancelled");
					Thread.currentThread().interrupt();
					throw error;
				} catch (ExecutionException error) {
					Throwable cause = error.getCause();
					if (cause instanceof Exception exception) throw exception;
					throw new IOException(String.valueOf(cause), cause);
				}
			}
		} finally {
			pending.remove(id);
		}
	}

	@Override
	public void notify(String method, ObjectNode params) throws IOException {
		ObjectNode message = Json.object().put("jsonrpc", "2.0").put("method", method);
		if (params != null) message.set("params", params);
		write(message);
	}

	@Override
	public void onNotification(BiConsumer<String, JsonNode> listener) {
		notificationListener = listener == null ? (method, params) -> {} : listener;
	}

	private void readMessages() {
		try (BufferedReader input = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (!closed && (line = input.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonNode message;
				try {
					message = Json.MAPPER.readTree(line);
				} catch (IOException malformed) {
					appendStderr("Invalid JSON on MCP stdout: " + line + "\n");
					continue;
				}
				dispatch(message);
			}
			if (!closed) failPending(new IOException(exitMessage()));
		} catch (IOException error) {
			if (!closed) failPending(error);
		}
	}

	private void dispatch(JsonNode message) {
		if (message.isArray()) {
			for (JsonNode item : message) dispatch(item);
			return;
		}
		if (!message.isObject()) return;
		JsonNode method = message.get("method");
		JsonNode id = message.get("id");
		if (method != null && method.isTextual()) {
			JsonNode params = message.get("params");
			if (id != null && !id.isNull()) respondToServerRequest(id, method.asText(), params);
			else notificationListener.accept(method.asText(), params);
			return;
		}
		if (id == null || !id.canConvertToLong()) return;
		CompletableFuture<JsonNode> future = pending.get(id.asLong());
		if (future == null) return;
		JsonNode error = message.get("error");
		if (error != null && !error.isNull()) future.completeExceptionally(rpcError(error));
		else future.complete(message.get("result"));
	}

	private void respondToServerRequest(JsonNode id, String method, JsonNode params) {
		ObjectNode response = Json.object().put("jsonrpc", "2.0");
		response.set("id", id);
		switch (method) {
			case "ping" -> response.set("result", Json.object());
			case "roots/list" -> {
				ObjectNode result = Json.object();
				result.putArray("roots")
						.addObject()
						.put("uri", workspace.toUri().toString())
						.put("name", workspace.getFileName() == null ? workspace.toString() : workspace.getFileName().toString());
				response.set("result", result);
			}
			default -> response.set(
					"error",
					Json.object().put("code", -32601).put("message", "Client does not support " + method));
		}
		try {
			write(response);
		} catch (IOException ignored) {
			// A transport failure is reported to pending client requests by the reader/process watcher.
		}
	}

	private void cancel(long id, String reason) {
		pending.remove(id);
		try {
			notify("notifications/cancelled", Json.object().put("requestId", id).put("reason", reason));
		} catch (IOException ignored) {
			// Cancellation is best effort.
		}
	}

	private void write(JsonNode message) throws IOException {
		if (closed) throw new IOException("MCP stdio transport is closed");
		synchronized (writeLock) {
			writer.write(Json.MAPPER.writeValueAsString(message));
			writer.newLine();
			writer.flush();
		}
	}

	private void readStderr() {
		try (var input = process.getErrorStream()) {
			byte[] buffer = new byte[2_048];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				appendStderr(new String(buffer, 0, count, StandardCharsets.UTF_8));
			}
		} catch (IOException ignored) {
			// Stderr is diagnostic only.
		}
	}

	private void appendStderr(String value) {
		synchronized (stderr) {
			stderr.append(value);
			if (stderr.length() > STDERR_LIMIT) stderr.delete(0, stderr.length() - STDERR_LIMIT);
		}
	}

	private String exitMessage() {
		String detail;
		synchronized (stderr) {
			detail = stderr.toString().trim();
		}
		String status = process.isAlive() ? "MCP server closed its stdout" : "MCP server exited with code " + process.exitValue();
		return detail.isBlank() ? status : status + ": " + detail;
	}

	private void failPending(Exception error) {
		for (CompletableFuture<JsonNode> future : pending.values()) future.completeExceptionally(error);
		pending.clear();
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		failPending(new IOException("MCP stdio transport closed"));
		try {
			writer.close();
		} catch (IOException ignored) {}
		process.descendants().forEach(handle -> {
			try {
				handle.destroy();
			} catch (RuntimeException ignored) {}
		});
		process.destroy();
		try {
			if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	private static IOException rpcError(JsonNode error) {
		String message = error.path("message").asText("MCP JSON-RPC error");
		if (error.has("code")) message += " (" + error.path("code").asText() + ")";
		if (error.has("data")) message += ": " + error.path("data");
		return new IOException(message);
	}

	private static Path resolve(Path base, String value) {
		Path path = Path.of(value);
		return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
	}
}
