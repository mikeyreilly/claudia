package com.quaxt.codingagent.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The parsed MCP portion of codingagent settings and its source file. */
public final class McpConfiguration {
	public Map<String, McpServerConfig> servers;
	public List<Path> sources;

	public McpConfiguration(Map<String, McpServerConfig> servers, List<Path> sources) {
		this.servers = servers;
		this.sources = sources;
	}
}
