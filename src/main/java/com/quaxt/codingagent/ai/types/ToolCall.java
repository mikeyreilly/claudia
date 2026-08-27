package com.quaxt.codingagent.ai.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Tool call requested by the model. Arguments are parsed JSON.
 * thoughtSignature is Google-specific opaque thought context.
 */
public final class ToolCall implements AssistantContent {
	public String id;
	public String name;
	public ObjectNode arguments;
	public String thoughtSignature;

	public ToolCall(String id, String name, ObjectNode arguments, String thoughtSignature) {
		this.id = id;
		this.name = name;
		this.arguments = arguments;
		this.thoughtSignature = thoughtSignature;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ToolCall that
				&& Objects.equals(id, that.id)
				&& Objects.equals(name, that.name)
				&& Objects.equals(arguments, that.arguments)
				&& Objects.equals(thoughtSignature, that.thoughtSignature);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, name, arguments, thoughtSignature);
	}

	@Override
	public String toString() {
		return "ToolCall[id=" + id + ", name=" + name
				+ ", arguments=" + arguments
				+ ", thoughtSignature=" + thoughtSignature + "]";
	}
}
