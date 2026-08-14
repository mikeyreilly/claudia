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

	/** Optional recursive JSON result filters for tools on this server. */
	List<McpResultFilter> resultFilters();

	/** A local MCP server connected over newline-delimited JSON-RPC on stdio. */
	record Local(
			List<String> command,
			String cwd,
			Map<String, String> environment,
			boolean enabled,
			Long timeoutMillis,
			List<McpResultFilter> resultFilters)
			implements McpServerConfig {
		public Local {
			command = List.copyOf(command);
			environment = Map.copyOf(environment);
			resultFilters = List.copyOf(resultFilters);
		}

		public Local(
				List<String> command,
				String cwd,
				Map<String, String> environment,
				boolean enabled,
				Long timeoutMillis) {
			this(command, cwd, environment, enabled, timeoutMillis, List.of());
		}
	}

	/** A remote MCP server connected with Streamable HTTP (with legacy SSE fallback). */
	record Remote(
			URI url,
			Map<String, String> headers,
			JsonNode oauth,
			boolean enabled,
			Long timeoutMillis,
			List<McpResultFilter> resultFilters)
			implements McpServerConfig {
		public Remote {
			headers = Map.copyOf(headers);
			oauth = oauth == null ? null : oauth.deepCopy();
			resultFilters = List.copyOf(resultFilters);
		}

		public Remote(
				URI url,
				Map<String, String> headers,
				JsonNode oauth,
				boolean enabled,
				Long timeoutMillis) {
			this(url, headers, oauth, enabled, timeoutMillis, List.of());
		}
	}
}
