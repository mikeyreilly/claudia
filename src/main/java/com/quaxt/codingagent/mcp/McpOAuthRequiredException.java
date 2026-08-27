package com.quaxt.codingagent.mcp;

import java.io.IOException;

/** Signals that a remote MCP server needs an interactive OAuth authorization. */
public final class McpOAuthRequiredException extends IOException {
	public McpOAuthRequiredException(String message) {
		super(message);
	}
}
