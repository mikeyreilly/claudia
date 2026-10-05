package com.quaxt.claudia.mcp;

/**
 * Marker for one MCP JSON-RPC transport carrier. Requests, notifications, the
 * negotiated protocol version, notification listeners, and shutdown are static
 * operations in ClaudiaOperations that dispatch over the concrete carriers.
 */
public sealed interface McpTransport
		permits StdioMcpTransport, StreamableHttpMcpTransport, SseHttpMcpTransport {}
