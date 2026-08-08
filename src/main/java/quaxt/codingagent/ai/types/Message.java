package works.earendil.pi.ai.types;

/** A conversation message: user, assistant, or tool result. */
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
	/** Unix timestamp in milliseconds. */
	long timestamp();

	String role();
}
