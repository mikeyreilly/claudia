package com.quaxt.claudia.ai.types;

import java.util.Objects;

/**
 * Thinking/reasoning content block. thinkingSignature is the provider's opaque
 * signature (or encrypted payload when redacted).
 */
public final class ThinkingContent implements AssistantContent {
	public String thinking;
	public String thinkingSignature;
	public boolean redacted;

	public ThinkingContent(String thinking, String thinkingSignature, boolean redacted) {
		this.thinking = thinking;
		this.thinkingSignature = thinkingSignature;
		this.redacted = redacted;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ThinkingContent that
				&& Objects.equals(thinking, that.thinking)
				&& Objects.equals(thinkingSignature, that.thinkingSignature)
				&& redacted == that.redacted;
	}

	@Override
	public int hashCode() {
		return Objects.hash(thinking, thinkingSignature, redacted);
	}

	@Override
	public String toString() {
		return "ThinkingContent[thinking=" + thinking
				+ ", thinkingSignature=" + thinkingSignature
				+ ", redacted=" + redacted + "]";
	}
}
