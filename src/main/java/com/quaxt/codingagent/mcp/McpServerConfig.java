package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * OpenCode-compatible configuration for one Model Context Protocol server.
 * Marker for the two server carriers; the shared settings are read through the
 * static accessors in CodingAgentOperations.
 */
public sealed interface McpServerConfig permits McpServerConfig.Local, McpServerConfig.Remote {

	/** A local MCP server connected over newline-delimited JSON-RPC on stdio. */
	final class Local implements McpServerConfig {
		public List<String> command;
		public String cwd;
		public Map<String, String> environment;
		public boolean enabled;
		public Long timeoutMillis;
		public List<McpResultFilter> resultFilters;
		public List<String> disabledTools;
		/**
		 * True for a server codingagent supplies itself (see {@link BuiltInMcpServers})
		 * rather than one defined in the settings file's {@code mcp} object.
		 */
		public boolean builtIn;

		public Local(
				List<String> command,
				String cwd,
				Map<String, String> environment,
				boolean enabled,
				Long timeoutMillis,
				List<McpResultFilter> resultFilters,
				List<String> disabledTools) {
			this(command, cwd, environment, enabled, timeoutMillis, resultFilters, disabledTools, false);
		}

		public Local(
				List<String> command,
				String cwd,
				Map<String, String> environment,
				boolean enabled,
				Long timeoutMillis,
				List<McpResultFilter> resultFilters,
				List<String> disabledTools,
				boolean builtIn) {
			this.command = command;
			this.cwd = cwd;
			this.environment = environment;
			this.enabled = enabled;
			this.timeoutMillis = timeoutMillis;
			this.resultFilters = resultFilters;
			this.disabledTools = disabledTools;
			this.builtIn = builtIn;
		}
	}

	/** A remote MCP server connected with Streamable HTTP (with legacy SSE fallback). */
	final class Remote implements McpServerConfig {
		public URI url;
		public Map<String, String> headers;
		public JsonNode oauth;
		public boolean enabled;
		public Long timeoutMillis;
		public List<McpResultFilter> resultFilters;
		public List<String> disabledTools;

		public Remote(
				URI url,
				Map<String, String> headers,
				JsonNode oauth,
				boolean enabled,
				Long timeoutMillis,
				List<McpResultFilter> resultFilters,
				List<String> disabledTools) {
			this.url = url;
			this.headers = headers;
			this.oauth = oauth;
			this.enabled = enabled;
			this.timeoutMillis = timeoutMillis;
			this.resultFilters = resultFilters;
			this.disabledTools = disabledTools;
		}
	}
}
