package com.quaxt.codingagent.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The parsed MCP portion of codingagent settings and its source file. */
public final class McpConfiguration {
	public Map<String, McpServerConfig> servers;
	public List<Path> sources;
	/** Stored choices for built-in servers, keyed by server name; absent names use the default. */
	public Map<String, BuiltInMcpServers.Preference> builtInPreferences;

	public McpConfiguration(Map<String, McpServerConfig> servers, List<Path> sources) {
		this(servers, sources, Map.of());
	}

	public McpConfiguration(
			Map<String, McpServerConfig> servers,
			List<Path> sources,
			Map<String, BuiltInMcpServers.Preference> builtInPreferences) {
		this.servers = servers;
		this.sources = sources;
		this.builtInPreferences = builtInPreferences;
	}
}
