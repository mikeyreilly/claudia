package works.earendil.pi.ai.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Assistant message. Mutable: providers build it up while streaming and event
 * consumers observe the in-progress partial (mirrors the TS design where the
 * same `partial` object is emitted with every event).
 */
public final class AssistantMessage implements Message {
	public final List<AssistantContent> content = new ArrayList<>();
	public String api;
	public String provider;
	public String model;
	/** Concrete response model when different from the requested model. */
	public String responseModel;
	/** Provider-specific response/message identifier, when exposed. */
	public String responseId;
	public Usage usage = new Usage();
	public StopReason stopReason = StopReason.PENDING;
	public String errorMessage;
	public String rawStopReason;
	public long timestamp = System.currentTimeMillis();

	public AssistantMessage() {}

	public AssistantMessage(String api, String provider, String model) {
		this.api = api;
		this.provider = provider;
		this.model = model;
	}

	/** Concatenated text of all text blocks. */
	public String text() {
		StringBuilder sb = new StringBuilder();
		for (AssistantContent block : content) {
			if (block instanceof TextContent(String text, String ignored)) {
				sb.append(text);
			}
		}
		return sb.toString();
	}

	/** Concatenated text of all thinking blocks. */
	public String thinking() {
		StringBuilder sb = new StringBuilder();
		for (AssistantContent block : content) {
			if (block instanceof ThinkingContent tc) {
				sb.append(tc.thinking());
			}
		}
		return sb.toString();
	}

	public List<ToolCall> toolCalls() {
		List<ToolCall> calls = new ArrayList<>();
		for (AssistantContent block : content) {
			if (block instanceof ToolCall call) {
				calls.add(call);
			}
		}
		return calls;
	}

	@Override
	public long timestamp() {
		return timestamp;
	}

	@Override
	public String role() {
		return "assistant";
	}
}
