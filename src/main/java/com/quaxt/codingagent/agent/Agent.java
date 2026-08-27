package com.quaxt.codingagent.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.Retry;
import com.quaxt.codingagent.ai.util.AbortSignal;

/**
 * Carrier for one stateful coding-agent loop: conversation state, the provider
 * the loop streams from, its event listeners, and the signal of the in-flight
 * turn. The loop itself (prompting, streaming, retries, compaction,
 * cancellation, and tool execution) lives in CodingAgentOperations.
 */
public final class Agent {
	public AgentState state;
	public Provider provider;
	public List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
	public volatile AbortSignal activeSignal;
	public String apiKey;
	public Retry.Policy retryPolicy = Retry.Policy.DEFAULT;

	public Agent(AgentState state, Provider provider) {
		this.state = state;
		this.provider = provider;
	}
}
