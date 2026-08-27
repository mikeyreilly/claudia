package com.quaxt.codingagent.mcp;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Carrier for one loopback HTTP receiver of an OAuth authorization-code
 * redirect. Starting the server, handling the redirect, awaiting the code, and
 * shutdown live in CodingAgentOperations.
 */
public final class McpOAuthCallback {
	public URI redirectUri;
	public String expectedState;
	public HttpServer server;
	public ExecutorService executor;
	public CompletableFuture<String> code = new CompletableFuture<>();

	public McpOAuthCallback() {}
}
