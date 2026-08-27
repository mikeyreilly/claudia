package com.quaxt.codingagent.agent;

import java.nio.file.Path;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.ToolResultMessage;

/** Events emitted while the agent processes a prompt. */
public sealed interface AgentEvent {
	record AgentStart() implements AgentEvent {}

	record AgentEnd(java.util.List<Message> newMessages) implements AgentEvent {}

	/** A repository instruction file was incorporated into the system prompt. */
	record InstructionLoaded(Path path) implements AgentEvent {}

	record CompactionStart(long tokensBefore) implements AgentEvent {}

	record CompactionEnd(CompactionResult result) implements AgentEvent {}

	record TurnStart() implements AgentEvent {}

	record TurnEnd(Message assistant, java.util.List<ToolResultMessage> toolResults) implements AgentEvent {}

	/** A transient provider failure will be retried after the indicated delay. */
	record AutoRetryStart(int attempt, int maxAttempts, long delayMs, String errorMessage) implements AgentEvent {}

	/** The retry sequence either recovered or reached a terminal failure. */
	record AutoRetryEnd(boolean success, int attempt, String finalError) implements AgentEvent {}

	record MessageStart(Message message) implements AgentEvent {}

	record MessageUpdate(AssistantMessageEvent providerEvent) implements AgentEvent {}

	record MessageEnd(Message message) implements AgentEvent {}

	record ToolExecutionStart(String toolCallId, String toolName, com.fasterxml.jackson.databind.node.ObjectNode arguments)
			implements AgentEvent {}

	record ToolExecutionUpdate(String toolCallId, String toolName, AgentTool.ToolResult partialResult)
			implements AgentEvent {}

	record ToolExecutionEnd(String toolCallId, String toolName, AgentTool.ToolResult result) implements AgentEvent {}
}
