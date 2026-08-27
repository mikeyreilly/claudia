package com.quaxt.codingagent.ai.types;

import java.util.List;
import java.util.Objects;

/** User message with text and/or image content. */
public final class UserMessage implements Message {
	public List<UserContent> content;
	public long timestamp;

	public UserMessage(List<UserContent> content, long timestamp) {
		this.content = content;
		this.timestamp = timestamp;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof UserMessage that
				&& Objects.equals(content, that.content)
				&& timestamp == that.timestamp;
	}

	@Override
	public int hashCode() {
		return Objects.hash(content, timestamp);
	}

	@Override
	public String toString() {
		return "UserMessage[content=" + content + ", timestamp=" + timestamp + "]";
	}
}
