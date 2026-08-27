package com.quaxt.codingagent.ai.types;

import java.util.List;
import java.util.Objects;

/**
 * Result of executing a tool call. `details` carries tool-specific structured
 * data that never goes to the model (rendered by UIs instead).
 */
public final class ToolResultMessage implements Message {
	public String toolCallId;
	public String toolName;
	public List<UserContent> content;
	public Object details;
	public boolean isError;
	public long timestamp;

	public ToolResultMessage(
			String toolCallId,
			String toolName,
			List<UserContent> content,
			Object details,
			boolean isError,
			long timestamp) {
		this.toolCallId = toolCallId;
		this.toolName = toolName;
		this.content = content;
		this.details = details;
		this.isError = isError;
		this.timestamp = timestamp;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ToolResultMessage that
				&& Objects.equals(toolCallId, that.toolCallId)
				&& Objects.equals(toolName, that.toolName)
				&& Objects.equals(content, that.content)
				&& Objects.equals(details, that.details)
				&& isError == that.isError
				&& timestamp == that.timestamp;
	}

	@Override
	public int hashCode() {
		return Objects.hash(toolCallId, toolName, content, details, isError, timestamp);
	}

	@Override
	public String toString() {
		return "ToolResultMessage[toolCallId=" + toolCallId
				+ ", toolName=" + toolName
				+ ", content=" + content
				+ ", details=" + details
				+ ", isError=" + isError
				+ ", timestamp=" + timestamp + "]";
	}
}
