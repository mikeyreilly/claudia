package com.quaxt.codingagent.ai.types;

/**
 * Event protocol for AssistantMessageEventStream. Mirrors AssistantMessageEvent
 * in packages/ai/src/types.ts. Streams emit Start first, then partial updates,
 * and terminate with Done (success) or Error (stopReason error/aborted).
 * `partial` is the same mutable AssistantMessage instance throughout.
 */
public sealed interface AssistantMessageEvent {
	record Start(AssistantMessage partial) implements AssistantMessageEvent {}

	record TextStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {}

	record TextDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {}

	record TextEnd(int contentIndex, String content, AssistantMessage partial) implements AssistantMessageEvent {}

	record ThinkingStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {}

	record ThinkingDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {}

	record ThinkingEnd(int contentIndex, String content, AssistantMessage partial) implements AssistantMessageEvent {}

	record ToolCallStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {}

	record ToolCallDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {}

	record ToolCallEnd(int contentIndex, ToolCall toolCall, AssistantMessage partial) implements AssistantMessageEvent {}

	/** Terminal success event. reason is STOP, LENGTH, TOOL_USE, or DEFERRED. */
	record Done(StopReason reason, AssistantMessage message) implements AssistantMessageEvent {}

	/** Terminal failure event. reason is ERROR or ABORTED. */
	record Error(StopReason reason, AssistantMessage error) implements AssistantMessageEvent {}
}
