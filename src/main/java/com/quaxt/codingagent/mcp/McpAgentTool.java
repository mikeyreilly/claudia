package com.quaxt.codingagent.mcp;

import java.util.List;
import com.quaxt.codingagent.agent.AgentTool;

/**
 * Carrier that binds one remote MCP tool to the client and result filters used
 * to invoke it. Metadata and execution live in CodingAgentOperations.
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
