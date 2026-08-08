package com.quaxt.codingagent.mcp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parsed MCP portion of codingagent settings and its source file. */
public record McpConfiguration(Map<String, McpServerConfig> servers, List<Path> sources) {
	public McpConfiguration {
		servers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(servers));
		sources = List.copyOf(sources);
	}

	public static McpConfiguration empty() {
		return new McpConfiguration(Map.of(), List.of());
	}
}
