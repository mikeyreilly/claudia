package com.quaxt.codingagent.agent;

import java.util.List;
import com.quaxt.codingagent.ai.types.UserContent;

/**
 * A model-visible tool. Registered definitions bind metadata, typed argument
 * validation, and a handler to runtime resources; function and MCP carriers
 * supply the other supported tool forms. CodingAgentOperations dispatches
 * these forms without knowing individual built-in tool names.
 */
public interface AgentTool {

	/** Result of one tool execution. */
	final class ToolResult {
		public List<UserContent> content;
		public Object details;
		public boolean isError;

		public ToolResult(List<UserContent> content, Object details, boolean isError) {
			this.content = content;
			this.details = details;
			this.isError = isError;
		}
	}
}
