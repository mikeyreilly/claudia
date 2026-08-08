package com.quaxt.codingagent.ai.types;

import java.util.List;

/** User message with text and/or image content. */
public record UserMessage(List<UserContent> content, long timestamp) implements Message {
	public UserMessage {
		content = List.copyOf(content);
	}

	public static UserMessage of(String text) {
		return new UserMessage(List.of(new TextContent(text)), System.currentTimeMillis());
	}

	public static UserMessage of(List<UserContent> content) {
		return new UserMessage(content, System.currentTimeMillis());
	}

	/** Concatenated text of all text blocks. */
	public String text() {
		StringBuilder sb = new StringBuilder();
		for (UserContent block : content) {
			if (block instanceof TextContent(String text, String ignored)) {
				sb.append(text);
			}
		}
		return sb.toString();
	}

	@Override
	public String role() {
		return "user";
	}
}
