package com.quaxt.codingagent.ai.types;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool call requested by the model. Arguments are parsed JSON.
 * thoughtSignature is Google-specific opaque thought context.
 */
public record ToolCall(String id, String name, ObjectNode arguments, String thoughtSignature)
		implements AssistantContent {
	public ToolCall {
		if (id == null || name == null || arguments == null) {
			throw new IllegalArgumentException("id, name, and arguments must not be null");
		}
	}

	public ToolCall(String id, String name, ObjectNode arguments) {
		this(id, name, arguments, null);
	}
}
