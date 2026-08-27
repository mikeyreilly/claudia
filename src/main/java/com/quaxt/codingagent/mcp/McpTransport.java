package com.quaxt.codingagent.mcp;

/**
 * Marker for one MCP JSON-RPC transport carrier. Requests, notifications, the
 * negotiated protocol version, notification listeners, and shutdown are static
 * operations in CodingAgentOperations that dispatch over the concrete carriers.
 */
public sealed interface McpTransport
		permits StdioMcpTransport, StreamableHttpMcpTransport, SseHttpMcpTransport {}
