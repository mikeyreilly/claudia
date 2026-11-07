package com.quaxt.claudia.ai.types;

import java.util.Objects;

/**
 * Text content block. textSignature carries provider metadata (e.g. OpenAI
 * responses message item ids); null when absent.
 */
public final class TextContent implements UserContent, AssistantContent {
	public String text;
	public String textSignature;

	public TextContent(String text, String textSignature) {
		this.text = text;
		this.textSignature = textSignature;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof TextContent that
				&& Objects.equals(text, that.text)
				&& Objects.equals(textSignature, that.textSignature);
	}

	@Override
	public int hashCode() {
		return Objects.hash(text, textSignature);
	}

	@Override
	public String toString() {
		return "TextContent[text=" + text + ", textSignature=" + textSignature + "]";
	}
}
