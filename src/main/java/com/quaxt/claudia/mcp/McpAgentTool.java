package com.quaxt.claudia.mcp;

import java.util.List;
import com.quaxt.claudia.agent.AgentTool;

/**
 * Carrier that binds one remote MCP tool to the client and result filters used
 * to invoke it. Metadata and execution live in ClaudiaOperations.
 */
public final class McpAgentTool implements AgentTool {
	public String serverName;
	public McpClient.ToolDefinition definition;
	public McpClient client;
	public List<McpResultFilter> resultFilters;
	public String name;

	public McpAgentTool(
			String serverName,
			McpClient.ToolDefinition definition,
			McpClient client,
			List<McpResultFilter> resultFilters,
			String name) {
		this.serverName = serverName;
		this.definition = definition;
		this.client = client;
		this.resultFilters = resultFilters;
		this.name = name;
	}
}
