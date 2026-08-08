package com.quaxt.codingagent.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** A model-visible tool and its local execution implementation. */
public interface AgentTool {
	String name();

	String description();

	ObjectNode parameters();

	ToolResult execute(String toolCallId, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> onUpdate)
			throws Exception;

	record ToolResult(java.util.List<com.quaxt.codingagent.ai.types.UserContent> content, Object details, boolean isError) {
		public ToolResult {
			content = java.util.List.copyOf(content);
		}

		public static ToolResult text(String text) {
			return new ToolResult(java.util.List.of(new TextContent(text)), null, false);
		}

		public static ToolResult error(String text) {
			return new ToolResult(java.util.List.of(new TextContent(text)), null, true);
		}
	}
}
