package works.earendil.pi.ai.types;

/**
 * Thinking/reasoning content block. thinkingSignature is the provider's opaque
 * signature (or encrypted payload when redacted).
 */
public record ThinkingContent(String thinking, String thinkingSignature, boolean redacted) implements AssistantContent {
	public ThinkingContent {
		if (thinking == null) {
			throw new IllegalArgumentException("thinking must not be null");
		}
	}

	public ThinkingContent(String thinking) {
		this(thinking, null, false);
	}

	public ThinkingContent withThinking(String newThinking) {
		return new ThinkingContent(newThinking, thinkingSignature, redacted);
	}

	public ThinkingContent withSignature(String signature) {
		return new ThinkingContent(thinking, signature, redacted);
	}
}
