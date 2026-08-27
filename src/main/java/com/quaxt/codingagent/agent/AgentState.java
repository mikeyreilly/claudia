package com.quaxt.codingagent.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

/** Mutable, observable state owned by an {@link Agent}. */
public final class AgentState {
	public String systemPrompt;
	public Model model;
	public ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
	public List<AgentTool> tools = new ArrayList<>();
	public List<Message> messages = new ArrayList<>();
	public boolean isStreaming;
	public AssistantMessage streamingMessage;
	public Set<String> pendingToolCalls = new LinkedHashSet<>();
	public String errorMessage;
	public boolean isCompacting;
	public boolean autoCompactionEnabled = true;
	public int compactionReserveTokens = 16_384;
	public String compactionSummary;

	public AgentState(String systemPrompt, Model model) {
		this.systemPrompt = systemPrompt;
		this.model = model;
	}
}
