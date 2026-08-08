package com.quaxt.codingagent.mcp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The merged MCP portion of the OpenCode configuration and the files that contributed to it. */
public record McpConfiguration(Map<String, McpServerConfig> servers, List<Path> sources) {
	public McpConfiguration {
		servers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(servers));
		sources = List.copyOf(sources);
	}

	public static McpConfiguration empty() {
		return new McpConfiguration(Map.of(), List.of());
	}
}
