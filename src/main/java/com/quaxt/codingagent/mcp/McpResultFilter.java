package com.quaxt.codingagent.mcp;

import java.util.List;

/**
 * Carrier for one noisy-key filter applied to JSON results of matching MCP
 * tools. Glob matching and result rewriting live in CodingAgentOperations.
 */
public final class McpResultFilter {
	public String tool;
	public List<String> dropKeys;

	public McpResultFilter(String tool, List<String> dropKeys) {
		this.tool = tool;
		this.dropKeys = dropKeys;
	}
}
