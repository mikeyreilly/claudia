package com.quaxt.codingagent.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.quaxt.codingagent.ai.Retry;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.Tool;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.ai.util.AbortSignal;

/**
 * Stateful coding-agent loop. Each iteration streams an assistant response;
 * tool calls are executed sequentially and appended as tool-result messages
 * before the next model request. The loop stops on a non-tool response, an
 * error/abort, or explicit cancellation.
 */
public final class Agent {
	private final AgentState state;
	private final StreamFunction streamFunction;
	private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
	private volatile AbortSignal activeSignal;
	private String apiKey;
	private Retry.Policy retryPolicy = Retry.Policy.DEFAULT;

	public Agent(String systemPrompt, com.quaxt.codingagent.ai.types.Model model, StreamFunction streamFunction) {
		this.state = new AgentState(systemPrompt, model);
		this.streamFunction = streamFunction;
	}

	public AgentState state() {
		return state;
	}

	/** Overrides environment-based provider authentication for future requests. */
	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	/** Configures automatic retries for transient provider and transport failures. */
	public void setRetryPolicy(Retry.Policy retryPolicy) {
		this.retryPolicy = java.util.Objects.requireNonNull(retryPolicy, "retryPolicy");
	}

	public AutoCloseable subscribe(Consumer<AgentEvent> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Reports that a repository instruction file will apply to later model requests. */
	public void instructionLoaded(Path path) {
		emit(new AgentEvent.InstructionLoaded(java.util.Objects.requireNonNull(path, "path")));
	}

	public void abort() {
		if (activeSignal != null) {
			activeSignal.abort();
		}
	}

	/**
	 * Summarizes all active messages in a separate model call, then replaces
	 * them with a single checkpoint message. The caller remains responsible for
	 * persisting the original transcript if it needs complete history.
	 */
	public CompactionResult compact(String customInstructions) throws InterruptedException {
		if (state.isStreaming || state.isCompacting) {
			throw new IllegalStateException("Agent is already processing");
		}
		if (state.messages.isEmpty()) {
			throw new IllegalStateException("Cannot compact an empty conversation");
		}
		state.isCompacting = true;
		AbortSignal signal = new AbortSignal();
		try {
			long tokensBefore = estimateTokens(state.messages);
			emit(new AgentEvent.CompactionStart(tokensBefore));
			Context context = new Context("You summarize coding-agent conversations. Do not continue the conversation. "
					+ "Return a concise structured checkpoint covering the goal, completed work, current state, decisions, and next steps.");
			String prompt = "<conversation>\n" + serialize(state.messages) + "\n</conversation>\n\n"
					+ (customInstructions == null || customInstructions.isBlank()
							? "Summarize this conversation for a future coding-agent turn."
							: "Summarize this conversation with this focus: " + customInstructions);
			context.messages.add(UserMessage.of(prompt));
			AssistantMessage response = complete(context, signal);
			if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
				throw new IllegalStateException("Compaction failed: " + response.errorMessage);
			}
			String summary = response.text();
			if (summary.isBlank()) {
				throw new IllegalStateException("Compaction failed: provider returned an empty summary");
			}
			state.messages.clear();
			state.compactionSummary = summary;
			state.messages.add(UserMessage.of("[Conversation checkpoint]\n" + summary));
			CompactionResult result = new CompactionResult(summary, tokensBefore, estimateTokens(state.messages));
			emit(new AgentEvent.CompactionEnd(result));
			return result;
		} finally {
			state.isCompacting = false;
		}
	}

	/** Runs a prompt to completion, returning only messages created during this invocation. */
	public List<Message> prompt(String text) throws InterruptedException {
		return prompt(UserMessage.of(text));
	}

	/** Runs one or more prompt messages to completion. */
	public List<Message> prompt(Message... prompts) throws InterruptedException {
		if (state.isStreaming || state.isCompacting) {
			throw new IllegalStateException("Agent is already processing");
		}
		activeSignal = new AbortSignal();
		state.errorMessage = null;
		state.isStreaming = true;
		List<Message> newMessages = new ArrayList<>();
		try {
			if (shouldAutoCompact()) {
				compactInternal();
			}
			emit(new AgentEvent.AgentStart());
			emit(new AgentEvent.TurnStart());
			for (Message prompt : prompts) {
				state.messages.add(prompt);
				newMessages.add(prompt);
				emit(new AgentEvent.MessageStart(prompt));
				emit(new AgentEvent.MessageEnd(prompt));
			}

			while (true) {
				AssistantMessage response = streamAssistant();
				newMessages.add(response);
				if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
					state.errorMessage = response.errorMessage;
					emit(new AgentEvent.TurnEnd(response, List.of()));
					break;
				}
				List<ToolResultMessage> results = executeTools(response);
				newMessages.addAll(results);
				emit(new AgentEvent.TurnEnd(response, results));
				if (results.isEmpty()) {
					break;
				}
				emit(new AgentEvent.TurnStart());
			}
			return List.copyOf(newMessages);
		} finally {
			state.isStreaming = false;
			state.streamingMessage = null;
			state.pendingToolCalls.clear();
			emit(new AgentEvent.AgentEnd(List.copyOf(newMessages)));
			activeSignal = null;
		}
	}

	private AssistantMessage streamAssistant() throws InterruptedException {
		AssistantMessage[] lastAttempt = new AssistantMessage[1];
		return Retry.retryAssistantCall(
				() -> lastAttempt[0] = streamAssistantOnce(),
				retryPolicy,
				activeSignal,
				retryCallbacks(() -> discardAssistantAttempt(lastAttempt[0])));
	}

	private AssistantMessage streamAssistantOnce() throws InterruptedException {
		Context context = new Context(state.systemPrompt);
		for (Message message : state.messages) {
			if (!(message instanceof AssistantMessage assistant)
					|| (assistant.stopReason != StopReason.ERROR && assistant.stopReason != StopReason.ABORTED)) {
				context.messages.add(message);
			}
		}
		for (AgentTool tool : state.tools) {
			context.tools.add(new Tool(tool.name(), tool.description(), tool.parameters()));
		}
		StreamOptions options =
				new StreamOptions().signal(activeSignal).reasoning(state.thinkingLevel).apiKey(apiKey);
		AssistantMessageEventStream stream = streamFunction.stream(state.model, context, options);
		AssistantMessage finalMessage = null;
		for (AssistantMessageEvent event : stream) {
			switch (event) {
				case AssistantMessageEvent.Start start -> {
					state.streamingMessage = start.partial();
					state.messages.add(start.partial());
					emit(new AgentEvent.MessageStart(start.partial()));
				}
				case AssistantMessageEvent.Done done -> finalMessage = done.message();
				case AssistantMessageEvent.Error error -> finalMessage = error.error();
				default -> emit(new AgentEvent.MessageUpdate(event));
			}
		}
		if (finalMessage == null) {
			finalMessage = stream.result();
		}
		if (state.streamingMessage != null) {
			state.messages.set(state.messages.size() - 1, finalMessage);
		} else {
			state.messages.add(finalMessage);
			emit(new AgentEvent.MessageStart(finalMessage));
		}
		state.streamingMessage = null;
		emit(new AgentEvent.MessageEnd(finalMessage));
		return finalMessage;
	}

	private void discardAssistantAttempt(AssistantMessage attempt) {
		state.streamingMessage = null;
		if (attempt == null) return;
		for (int index = state.messages.size() - 1; index >= 0; index--) {
			if (state.messages.get(index) == attempt) {
				state.messages.remove(index);
				return;
			}
		}
	}

	private Retry.Callbacks retryCallbacks(Runnable beforeRetryAttempt) {
		return new Retry.Callbacks() {
			@Override
			public void onRetryScheduled(
					int attempt, int maxAttempts, long delayMs, String errorMessage) {
				emit(new AgentEvent.AutoRetryStart(attempt, maxAttempts, delayMs, errorMessage));
			}

			@Override
			public void onRetryAttemptStart() {
				if (beforeRetryAttempt != null) beforeRetryAttempt.run();
			}

			@Override
			public void onRetryFinished(boolean success, int attempt, String finalError) {
				emit(new AgentEvent.AutoRetryEnd(success, attempt, finalError));
			}
		};
	}

	private void compactInternal() throws InterruptedException {
		long tokensBefore = estimateTokens(state.messages);
		state.isCompacting = true;
		AbortSignal signal = new AbortSignal();
		try {
			emit(new AgentEvent.CompactionStart(tokensBefore));
			Context context = new Context("You summarize coding-agent conversations. Do not continue the conversation. "
					+ "Return a concise structured checkpoint covering the goal, completed work, current state, decisions, and next steps.");
			context.messages.add(UserMessage.of("<conversation>\n" + serialize(state.messages)
					+ "\n</conversation>\n\nSummarize this conversation for a future coding-agent turn."));
			AssistantMessage response = complete(context, signal);
			if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED || response.text().isBlank()) {
				throw new IllegalStateException("Automatic compaction failed: " + response.errorMessage);
			}
			state.messages.clear();
			state.compactionSummary = response.text();
			state.messages.add(UserMessage.of("[Conversation checkpoint]\n" + response.text()));
			emit(new AgentEvent.CompactionEnd(new CompactionResult(response.text(), tokensBefore, estimateTokens(state.messages))));
		} finally {
			state.isCompacting = false;
		}
	}

	private AssistantMessage complete(Context context, AbortSignal signal) throws InterruptedException {
		return Retry.retryAssistantCall(
				() -> completeOnce(context, signal), retryPolicy, signal, retryCallbacks(null));
	}

	private AssistantMessage completeOnce(Context context, AbortSignal signal) throws InterruptedException {
		AssistantMessageEventStream stream = streamFunction.stream(
				state.model, context, new StreamOptions().signal(signal).reasoning(state.thinkingLevel).apiKey(apiKey).maxTokens(4_096));
		AssistantMessage response = null;
		for (AssistantMessageEvent event : stream) {
			if (event instanceof AssistantMessageEvent.Done done) response = done.message();
			if (event instanceof AssistantMessageEvent.Error error) response = error.error();
		}
		return response == null ? stream.result() : response;
	}

	private boolean shouldAutoCompact() {
		return state.autoCompactionEnabled
				&& state.messages.size() > 1
				&& state.model.contextWindow > state.compactionReserveTokens
				&& estimateTokens(state.messages) > state.model.contextWindow - state.compactionReserveTokens;
	}

	private static long estimateTokens(List<Message> messages) {
		long characters = 0;
		for (Message message : messages) {
			if (message instanceof UserMessage user) characters += user.text().length();
			else if (message instanceof AssistantMessage assistant) {
				characters += assistant.text().length() + assistant.thinking().length();
				for (ToolCall call : assistant.toolCalls()) characters += call.name().length() + call.arguments().toString().length();
			} else if (message instanceof ToolResultMessage result) characters += result.text().length();
		}
		return (characters + 3) / 4;
	}

	private static String serialize(List<Message> messages) {
		StringBuilder output = new StringBuilder();
		for (Message message : messages) {
			if (message instanceof UserMessage user) output.append("[User] ").append(user.text());
			else if (message instanceof AssistantMessage assistant) {
				if (!assistant.thinking().isBlank()) output.append("[Assistant thinking] ").append(assistant.thinking()).append('\n');
				output.append("[Assistant] ").append(assistant.text());
				for (ToolCall call : assistant.toolCalls()) output.append("\n[Tool call] ").append(call.name()).append(' ').append(call.arguments());
			} else if (message instanceof ToolResultMessage result) output.append("[Tool result] ").append(result.text());
			output.append("\n\n");
		}
		return output.toString();
	}

	private List<ToolResultMessage> executeTools(AssistantMessage message) {
		Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
		for (AgentTool tool : state.tools) {
			toolsByName.put(tool.name(), tool);
		}
		List<ToolResultMessage> results = new ArrayList<>();
		for (ToolCall call : message.toolCalls()) {
			if (activeSignal.isAborted()) {
				break;
			}
			state.pendingToolCalls.add(call.id());
			emit(new AgentEvent.ToolExecutionStart(call.id(), call.name(), call.arguments()));
			AgentTool.ToolResult result;
			AgentTool tool = toolsByName.get(call.name());
			if (tool == null) {
				result = AgentTool.ToolResult.error("Unknown tool: " + call.name());
			} else {
				try {
					result = tool.execute(
							call.id(),
							call.arguments(),
							activeSignal,
							partial -> emit(new AgentEvent.ToolExecutionUpdate(call.id(), call.name(), partial)));
				} catch (Exception e) {
					result = AgentTool.ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
				}
			}
			state.pendingToolCalls.remove(call.id());
			emit(new AgentEvent.ToolExecutionEnd(call.id(), call.name(), result));
			ToolResultMessage toolResult =
					new ToolResultMessage(call.id(), call.name(), result.content(), result.details(), result.isError(), System.currentTimeMillis());
			state.messages.add(toolResult);
			results.add(toolResult);
			emit(new AgentEvent.MessageStart(toolResult));
			emit(new AgentEvent.MessageEnd(toolResult));
		}
		return results;
	}

	private void emit(AgentEvent event) {
		for (Consumer<AgentEvent> listener : listeners) {
			listener.accept(event);
		}
	}
}
