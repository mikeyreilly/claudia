package com.quaxt.claudia.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import com.quaxt.claudia.ai.types.AssistantMessageEvent;
import com.quaxt.claudia.ai.types.Message;
import com.quaxt.claudia.ai.types.ToolResultMessage;

/**
 * Events emitted while the agent processes a prompt. Marker interface; the
 * sealed hierarchy is retained so consumers can pattern match exhaustively over
 * the event kinds.
 */
public sealed interface AgentEvent
		permits AgentEvent.AgentStart,
				AgentEvent.AgentEnd,
				AgentEvent.InstructionLoaded,
				AgentEvent.CompactionStart,
				AgentEvent.CompactionEnd,
				AgentEvent.TurnStart,
				AgentEvent.TurnEnd,
				AgentEvent.AutoRetryStart,
				AgentEvent.AutoRetryEnd,
				AgentEvent.MessageStart,
				AgentEvent.MessageUpdate,
				AgentEvent.MessageEnd,
				AgentEvent.ToolExecutionStart,
				AgentEvent.ToolExecutionUpdate,
				AgentEvent.ToolExecutionEnd {

	final class AgentStart implements AgentEvent {
		public AgentStart() {}
	}

	final class AgentEnd implements AgentEvent {
		public List<Message> newMessages;

		public AgentEnd(List<Message> newMessages) {
			this.newMessages = newMessages;
		}
	}

	/** An agent instruction file was incorporated into the system prompt. */
	final class InstructionLoaded implements AgentEvent {
		public Path path;

		public InstructionLoaded(Path path) {
			this.path = path;
		}
	}

	final class CompactionStart implements AgentEvent {
		public long tokensBefore;

		public CompactionStart(long tokensBefore) {
			this.tokensBefore = tokensBefore;
		}
	}

	final class CompactionEnd implements AgentEvent {
		public CompactionResult result;

		public CompactionEnd(CompactionResult result) {
			this.result = result;
		}
	}

	final class TurnStart implements AgentEvent {
		public TurnStart() {}
	}

	final class TurnEnd implements AgentEvent {
		public Message assistant;
		public List<ToolResultMessage> toolResults;

		public TurnEnd(Message assistant, List<ToolResultMessage> toolResults) {
			this.assistant = assistant;
			this.toolResults = toolResults;
		}
	}

	/** A transient provider failure will be retried after the indicated delay. */
	final class AutoRetryStart implements AgentEvent {
		public int attempt;
		public int maxAttempts;
		public long delayMs;
		public String errorMessage;

		public AutoRetryStart(int attempt, int maxAttempts, long delayMs, String errorMessage) {
			this.attempt = attempt;
			this.maxAttempts = maxAttempts;
			this.delayMs = delayMs;
			this.errorMessage = errorMessage;
		}
	}

	/** The retry sequence either recovered or reached a terminal failure. */
	final class AutoRetryEnd implements AgentEvent {
		public boolean success;
		public int attempt;
		public String finalError;

		public AutoRetryEnd(boolean success, int attempt, String finalError) {
			this.success = success;
			this.attempt = attempt;
			this.finalError = finalError;
		}
	}

	final class MessageStart implements AgentEvent {
		public Message message;

		public MessageStart(Message message) {
			this.message = message;
		}
	}

	final class MessageUpdate implements AgentEvent {
		public AssistantMessageEvent providerEvent;

		public MessageUpdate(AssistantMessageEvent providerEvent) {
			this.providerEvent = providerEvent;
		}
	}

	final class MessageEnd implements AgentEvent {
		public Message message;

		public MessageEnd(Message message) {
			this.message = message;
		}
	}

	final class ToolExecutionStart implements AgentEvent {
		public String toolCallId;
		public String toolName;
		public ObjectNode arguments;

		public ToolExecutionStart(String toolCallId, String toolName, ObjectNode arguments) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.arguments = arguments;
		}
	}

	final class ToolExecutionUpdate implements AgentEvent {
		public String toolCallId;
		public String toolName;
		public AgentTool.ToolResult partialResult;

		public ToolExecutionUpdate(String toolCallId, String toolName, AgentTool.ToolResult partialResult) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.partialResult = partialResult;
		}
	}

	final class ToolExecutionEnd implements AgentEvent {
		public String toolCallId;
		public String toolName;
		public AgentTool.ToolResult result;

		public ToolExecutionEnd(String toolCallId, String toolName, AgentTool.ToolResult result) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.result = result;
		}
	}
}
