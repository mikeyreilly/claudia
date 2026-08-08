package works.earendil.pi.ai.types;

import java.util.List;

/**
 * Result of executing a tool call. `details` carries tool-specific structured
 * data that never goes to the model (rendered by UIs instead).
 */
public record ToolResultMessage(
		String toolCallId,
		String toolName,
		List<UserContent> content,
		Object details,
		boolean isError,
		long timestamp)
		implements Message {
	public ToolResultMessage {
		content = List.copyOf(content);
	}

	public static ToolResultMessage text(String toolCallId, String toolName, String text, boolean isError) {
		return new ToolResultMessage(
				toolCallId, toolName, List.of(new TextContent(text)), null, isError, System.currentTimeMillis());
	}

	/** Concatenated text of all text blocks. */
	public String text() {
		StringBuilder sb = new StringBuilder();
		for (UserContent block : content) {
			if (block instanceof TextContent(String t, String ignored)) {
				sb.append(t);
			}
		}
		return sb.toString();
	}

	@Override
	public String role() {
		return "toolResult";
	}
}
