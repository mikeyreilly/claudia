package com.quaxt.codingagent.mcp;

import java.io.IOException;

/** Signals that a remote MCP server needs an interactive OAuth authorization. */
final class McpOAuthRequiredException extends IOException {
	McpOAuthRequiredException(String serverName, boolean additionalScopes) {
		super(additionalScopes
				? "MCP server \"" + serverName + "\" requires additional OAuth permissions; press Enter to authorize"
				: "MCP server \"" + serverName + "\" requires OAuth authentication; press Enter to authorize");
	}
}
