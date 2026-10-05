package com.quaxt.claudia.ai.types;

import java.util.ArrayList;
import java.util.List;

/** LLM request context: system prompt, conversation, and available tools. */
public final class Context {
	public String systemPrompt;
	public List<Message> messages = new ArrayList<>();
	public List<Tool> tools = new ArrayList<>();

	public Context() {}

	public Context(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}
}
