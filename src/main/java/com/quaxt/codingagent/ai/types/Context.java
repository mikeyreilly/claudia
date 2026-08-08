package com.quaxt.codingagent.ai.types;

import java.util.ArrayList;
import java.util.List;

/** LLM request context: system prompt, conversation, and available tools. */
public final class Context {
	public String systemPrompt;
	public final List<Message> messages = new ArrayList<>();
	public final List<Tool> tools = new ArrayList<>();

	public Context() {}

	public Context(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public Context copy() {
		Context copy = new Context(systemPrompt);
		copy.messages.addAll(messages);
		copy.tools.addAll(tools);
		return copy;
	}
}
