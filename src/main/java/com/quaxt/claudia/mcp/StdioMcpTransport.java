package com.quaxt.claudia.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Carrier for MCP's newline-delimited JSON-RPC stdio transport: the child
 * process, its writer, the pending request table, and the captured stderr tail.
 * All transport behavior lives in ClaudiaOperations; {@code writeLock}
 * guards writes and {@code stderr} guards its own buffer.
 */
public final class StdioMcpTransport implements McpTransport {
	public static final int STDERR_LIMIT = 16 * 1024;

	public Process process;
	public BufferedWriter writer;
	public Object writeLock = new Object();
	public AtomicLong nextId = new AtomicLong(1);
	public Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	public Path workspace;
	public StringBuilder stderr = new StringBuilder();
	public volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> {};
	public volatile boolean closed;

	public StdioMcpTransport() {}
}
