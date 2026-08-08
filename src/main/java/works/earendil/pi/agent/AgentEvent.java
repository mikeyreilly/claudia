package works.earendil.pi.agent;

import works.earendil.pi.ai.types.AssistantMessageEvent;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.ToolResultMessage;

/** Events emitted while the agent processes a prompt. */
public sealed interface AgentEvent {
	record AgentStart() implements AgentEvent {}

	record AgentEnd(java.util.List<Message> newMessages) implements AgentEvent {}

	record CompactionStart(long tokensBefore) implements AgentEvent {}

	record CompactionEnd(CompactionResult result) implements AgentEvent {}

	record TurnStart() implements AgentEvent {}

	record TurnEnd(Message assistant, java.util.List<ToolResultMessage> toolResults) implements AgentEvent {}

	record MessageStart(Message message) implements AgentEvent {}

	record MessageUpdate(AssistantMessageEvent providerEvent) implements AgentEvent {}

	record MessageEnd(Message message) implements AgentEvent {}

	record ToolExecutionStart(String toolCallId, String toolName, com.fasterxml.jackson.databind.node.ObjectNode arguments)
			implements AgentEvent {}

	record ToolExecutionUpdate(String toolCallId, String toolName, AgentTool.ToolResult partialResult)
			implements AgentEvent {}

	record ToolExecutionEnd(String toolCallId, String toolName, AgentTool.ToolResult result) implements AgentEvent {}
}
