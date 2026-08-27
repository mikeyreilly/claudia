package com.quaxt.codingagent.ai.types;

/**
 * Event protocol for AssistantMessageEventStream. Mirrors AssistantMessageEvent
 * in packages/ai/src/types.ts. Streams emit Start first, then partial updates,
 * and terminate with Done (success) or Error (stopReason error/aborted).
 * `partial` is the same mutable AssistantMessage instance throughout.
 *
 * <p>Marker interface; the sealed hierarchy is retained so consumers can pattern
 * match exhaustively over the event kinds.
 */
public sealed interface AssistantMessageEvent
		permits AssistantMessageEvent.Start,
				AssistantMessageEvent.TextStart,
				AssistantMessageEvent.TextDelta,
				AssistantMessageEvent.TextEnd,
				AssistantMessageEvent.ThinkingStart,
				AssistantMessageEvent.ThinkingDelta,
				AssistantMessageEvent.ThinkingEnd,
				AssistantMessageEvent.ToolCallStart,
				AssistantMessageEvent.ToolCallDelta,
				AssistantMessageEvent.ToolCallEnd,
				AssistantMessageEvent.Done,
				AssistantMessageEvent.Error {

	final class Start implements AssistantMessageEvent {
		public AssistantMessage partial;

		public Start(AssistantMessage partial) {
			this.partial = partial;
		}
	}

	final class TextStart implements AssistantMessageEvent {
		public int contentIndex;
		public AssistantMessage partial;

		public TextStart(int contentIndex, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.partial = partial;
		}
	}

	final class TextDelta implements AssistantMessageEvent {
		public int contentIndex;
		public String delta;
		public AssistantMessage partial;

		public TextDelta(int contentIndex, String delta, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.delta = delta;
			this.partial = partial;
		}
	}

	final class TextEnd implements AssistantMessageEvent {
		public int contentIndex;
		public String content;
		public AssistantMessage partial;

		public TextEnd(int contentIndex, String content, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.content = content;
			this.partial = partial;
		}
	}

	final class ThinkingStart implements AssistantMessageEvent {
		public int contentIndex;
		public AssistantMessage partial;

		public ThinkingStart(int contentIndex, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.partial = partial;
		}
	}

	final class ThinkingDelta implements AssistantMessageEvent {
		public int contentIndex;
		public String delta;
		public AssistantMessage partial;

		public ThinkingDelta(int contentIndex, String delta, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.delta = delta;
			this.partial = partial;
		}
	}

	final class ThinkingEnd implements AssistantMessageEvent {
		public int contentIndex;
		public String content;
		public AssistantMessage partial;

		public ThinkingEnd(int contentIndex, String content, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.content = content;
			this.partial = partial;
		}
	}

	final class ToolCallStart implements AssistantMessageEvent {
		public int contentIndex;
		public AssistantMessage partial;

		public ToolCallStart(int contentIndex, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.partial = partial;
		}
	}

	final class ToolCallDelta implements AssistantMessageEvent {
		public int contentIndex;
		public String delta;
		public AssistantMessage partial;

		public ToolCallDelta(int contentIndex, String delta, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.delta = delta;
			this.partial = partial;
		}
	}

	final class ToolCallEnd implements AssistantMessageEvent {
		public int contentIndex;
		public ToolCall toolCall;
		public AssistantMessage partial;

		public ToolCallEnd(int contentIndex, ToolCall toolCall, AssistantMessage partial) {
			this.contentIndex = contentIndex;
			this.toolCall = toolCall;
			this.partial = partial;
		}
	}

	/** Terminal success event. reason is STOP, LENGTH, TOOL_USE, or DEFERRED. */
	final class Done implements AssistantMessageEvent {
		public StopReason reason;
		public AssistantMessage message;

		public Done(StopReason reason, AssistantMessage message) {
			this.reason = reason;
			this.message = message;
		}
	}

	/** Terminal failure event. reason is ERROR or ABORTED. */
	final class Error implements AssistantMessageEvent {
		public StopReason reason;
		public AssistantMessage error;

		public Error(StopReason reason, AssistantMessage error) {
			this.reason = reason;
			this.error = error;
		}
	}
}
