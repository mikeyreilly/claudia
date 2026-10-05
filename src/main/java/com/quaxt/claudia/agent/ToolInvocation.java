package com.quaxt.claudia.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import com.quaxt.claudia.ai.util.AbortSignal;

/** Inputs handed to a tool for one execution. */
public final class ToolInvocation {
	public String toolCallId;
	public ObjectNode arguments;
	public AbortSignal signal;
	public Consumer<AgentTool.ToolResult> onUpdate;

	public ToolInvocation(
			String toolCallId, ObjectNode arguments, AbortSignal signal, Consumer<AgentTool.ToolResult> onUpdate) {
		this.toolCallId = toolCallId;
		this.arguments = arguments;
		this.signal = signal;
		this.onUpdate = onUpdate;
	}
}
