package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;

/**
 * Carrier for one initialized MCP client session: its transport, request
 * timeout, negotiated capabilities, and server instructions. Connecting,
 * listing tools, calling tools, and shutdown live in CodingAgentOperations.
 */
public final class McpClient {
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
	public static final String PROTOCOL_VERSION = "2025-11-25";
	public static final int MAX_LIST_PAGES = 1_000;

	public McpTransport transport;
	public Duration timeout;
	public ObjectNode capabilities;
	public String instructions;

	public McpClient() {}

	/** One tool advertised by an MCP server. */
	public static final class ToolDefinition {
		public String name;
		public String description;
		public ObjectNode inputSchema;

		public ToolDefinition(String name, String description, ObjectNode inputSchema) {
			this.name = name;
			this.description = description;
			this.inputSchema = inputSchema;
		}
	}

	/** Raw result of one {@code tools/call} request. */
	public static final class CallResult {
		public ObjectNode raw;
		public boolean isError;

		public CallResult(ObjectNode raw, boolean isError) {
			this.raw = raw;
			this.isError = isError;
		}
	}
}
