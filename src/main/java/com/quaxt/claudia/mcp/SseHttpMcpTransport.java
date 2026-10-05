package com.quaxt.claudia.mcp;

import com.quaxt.claudia.ClaudiaOperations;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Carrier for the compatibility transport of the MCP 2024-11-05 HTTP+SSE
 * protocol: the SSE endpoint discovered from the event stream, the pending
 * request table, and the open event stream. All transport behavior lives in
 * ClaudiaOperations.
 */
public final class SseHttpMcpTransport implements McpTransport {
	public URI url;
	public Map<String, String> headers;
	public ClaudiaOperations.McpOAuthSession oauth;
	public Path workspace;
	public HttpClient client = ClaudiaOperations.newHttpClient();
	public AtomicLong nextId = new AtomicLong(1);
	public Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	public CompletableFuture<URI> endpoint = new CompletableFuture<>();
	public volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> {};
	public volatile CompletableFuture<HttpResponse<InputStream>> opening;
	public volatile InputStream eventStream;
	public volatile String protocolVersion;
	public volatile String sessionId;
	public volatile boolean closed;

	public SseHttpMcpTransport() {}
}
