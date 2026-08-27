package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Carrier for the MCP Streamable HTTP transport (protocol version 2025-03-26
 * and newer): the endpoint, configured headers, OAuth session, workspace root,
 * HTTP client, and the optional server-to-client listener stream. All transport
 * behavior lives in CodingAgentOperations.
 */
public final class StreamableHttpMcpTransport implements McpTransport {
	public URI url;
	public Map<String, String> headers;
	public McpOAuthClient.Session oauth;
	public Path workspace;
	public HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	public AtomicLong nextId = new AtomicLong(1);
	public volatile String sessionId;
	public volatile String protocolVersion;
	public volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> {};
	public volatile InputStream listenerStream;
	public volatile CompletableFuture<?> listenerRequest;
	public volatile boolean closed;

	public StreamableHttpMcpTransport() {}
}
