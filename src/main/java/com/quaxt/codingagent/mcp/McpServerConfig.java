package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** OpenCode-compatible configuration for one Model Context Protocol server. */
public sealed interface McpServerConfig permits McpServerConfig.Local, McpServerConfig.Remote {
	boolean enabled();

	/** Request and startup timeout in milliseconds, or {@code null} for the default. */
	Long timeoutMillis();

	/** A local MCP server connected over newline-delimited JSON-RPC on stdio. */
	record Local(
			List<String> command,
			String cwd,
			Map<String, String> environment,
			boolean enabled,
			Long timeoutMillis)
			implements McpServerConfig {
		public Local {
			command = List.copyOf(command);
			environment = Map.copyOf(environment);
		}
	}

	/** A remote MCP server connected with Streamable HTTP (with legacy SSE fallback). */
	record Remote(
			URI url,
			Map<String, String> headers,
			JsonNode oauth,
			boolean enabled,
			Long timeoutMillis)
			implements McpServerConfig {
		public Remote {
			headers = Map.copyOf(headers);
			oauth = oauth == null ? null : oauth.deepCopy();
		}
	}
}
