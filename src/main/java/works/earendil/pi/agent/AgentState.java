package works.earendil.pi.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.ThinkingLevel;

/** Mutable, observable state owned by an {@link Agent}. */
public final class AgentState {
	public String systemPrompt;
	public Model model;
	public ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
	public final List<AgentTool> tools = new ArrayList<>();
	public final List<Message> messages = new ArrayList<>();
	public boolean isStreaming;
	public AssistantMessage streamingMessage;
	public final Set<String> pendingToolCalls = new LinkedHashSet<>();
	public String errorMessage;
	public boolean isCompacting;
	public boolean autoCompactionEnabled = true;
	public int compactionReserveTokens = 16_384;
	public String compactionSummary;

	AgentState(String systemPrompt, Model model) {
		this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
		this.model = model;
	}
}
