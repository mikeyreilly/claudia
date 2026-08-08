package com.quaxt.codingagent.ai.types;

/**
 * Text content block. textSignature carries provider metadata (e.g. OpenAI
 * responses message item ids); null when absent.
 */
public record TextContent(String text, String textSignature) implements UserContent, AssistantContent {
	public TextContent {
		if (text == null) {
			throw new IllegalArgumentException("text must not be null");
		}
	}

	public TextContent(String text) {
		this(text, null);
	}

	public TextContent withText(String newText) {
		return new TextContent(newText, textSignature);
	}
}
