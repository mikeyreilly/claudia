package com.quaxt.codingagent.agent;

import java.util.List;
import com.quaxt.codingagent.ai.types.UserContent;

/**
 * Marker for a model-visible tool carrier. Tool metadata (name, description,
 * parameters) and execution are supplied by the static operations in
 * CodingAgentOperations, which dispatch over the concrete tool carriers.
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
