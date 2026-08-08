package com.quaxt.codingagent.ai.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import com.quaxt.codingagent.ai.Models;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantContent;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.Usage;

/**
 * Deterministic in-process provider for tests. This is the Java equivalent of
 * {@code packages/ai/src/providers/faux.ts}; it needs neither credentials nor
 * a network connection and produces the same incremental event protocol as
 * real provider adapters.
 */
public final class FauxProvider implements Provider {
	public static final String DEFAULT_API = "faux";
	public static final String DEFAULT_PROVIDER = "faux";
	public static final String DEFAULT_MODEL_ID = "faux-1";

	private final String api;
	private final String id;
	private final List<Model> models;
	private final Deque<ResponseStep> pendingResponses = new ArrayDeque<>();
	private final State state = new State();

	public FauxProvider() {
		this(DEFAULT_API, DEFAULT_PROVIDER, List.of(defaultModel()));
	}

	public FauxProvider(String api, String id, List<Model> models) {
		this.api = Objects.requireNonNull(api);
		this.id = Objects.requireNonNull(id);
		this.models = List.copyOf(models);
		if (this.models.isEmpty()) {
			throw new IllegalArgumentException("Faux provider needs at least one model");
		}
	}

	public static Model defaultModel() {
		return Model.builder()
				.id(DEFAULT_MODEL_ID)
				.name("Faux Model")
				.api(DEFAULT_API)
				.provider(DEFAULT_PROVIDER)
				.baseUrl("http://localhost:0")
				.input(List.of("text", "image"))
				.cost(ModelCost.FREE)
				.contextWindow(128_000)
				.maxTokens(16_384)
				.build();
	}

	/** Adds literal or context-dependent responses to the provider's FIFO script. */
	public synchronized void setResponses(List<ResponseStep> responses) {
		pendingResponses.clear();
		pendingResponses.addAll(responses);
	}

	public synchronized void appendResponses(List<ResponseStep> responses) {
		pendingResponses.addAll(responses);
	}

	public synchronized int pendingResponseCount() {
		return pendingResponses.size();
	}

	public State state() {
		return state;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return "Faux";
	}

	@Override
	public String api() {
		return api;
	}

	@Override
	public List<Model> models() {
		return models;
	}

	@Override
	public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		ResponseStep step;
		synchronized (this) {
			step = pendingResponses.pollFirst();
			state.callCount++;
		}
		Thread.startVirtualThread(() -> produce(stream, model, context, options != null ? options : new StreamOptions(), step));
		return stream;
	}

	private void produce(
			AssistantMessageEventStream stream, Model model, Context context, StreamOptions options, ResponseStep step) {
		try {
			AssistantMessage response = step == null
					? errorMessage(model, "No more faux responses queued")
					: step.resolve(new Request(context, options, state, model));
			response.api = api;
			response.provider = id;
			response.model = model.id;
			if (response.usage == null) {
				response.usage = new Usage();
			}
			estimateUsage(response, context);
			emit(stream, response, options);
		} catch (Exception e) {
			AssistantMessage error = errorMessage(model, e.getMessage() != null ? e.getMessage() : e.toString());
			stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, error));
		}
	}

	private static void emit(AssistantMessageEventStream stream, AssistantMessage response, StreamOptions options) {
		AssistantMessage partial = new AssistantMessage(response.api, response.provider, response.model);
		partial.responseId = response.responseId;
		partial.usage = response.usage;
		stream.push(new AssistantMessageEvent.Start(partial));

		for (int index = 0; index < response.content.size(); index++) {
			if (options.isAborted()) {
				abort(stream, partial);
				return;
			}
			AssistantContent block = response.content.get(index);
			if (block instanceof TextContent text) {
				emitText(stream, partial, index, text, options);
			} else if (block instanceof ThinkingContent thinking) {
				emitThinking(stream, partial, index, thinking, options);
			} else if (block instanceof ToolCall call) {
				emitToolCall(stream, partial, index, call, options);
			}
			if (options.isAborted()) {
				abort(stream, partial);
				return;
			}
		}

		copyTerminalFields(response, partial);
		if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
			stream.push(new AssistantMessageEvent.Error(response.stopReason, partial));
		} else if (response.stopReason == StopReason.PENDING) {
			partial.stopReason = StopReason.ERROR;
			partial.errorMessage = "Faux response ended without a stop reason";
			stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, partial));
		} else {
			stream.push(new AssistantMessageEvent.Done(response.stopReason, partial));
		}
	}

	private static void emitText(
			AssistantMessageEventStream stream, AssistantMessage partial, int index, TextContent text, StreamOptions options) {
		partial.content.add(new TextContent("", text.textSignature()));
		stream.push(new AssistantMessageEvent.TextStart(index, partial));
		StringBuilder value = new StringBuilder();
		for (String chunk : chunks(text.text())) {
			if (options.isAborted()) {
				return;
			}
			value.append(chunk);
			partial.content.set(index, new TextContent(value.toString(), text.textSignature()));
			stream.push(new AssistantMessageEvent.TextDelta(index, chunk, partial));
		}
		stream.push(new AssistantMessageEvent.TextEnd(index, text.text(), partial));
	}

	private static void emitThinking(
			AssistantMessageEventStream stream,
			AssistantMessage partial,
			int index,
			ThinkingContent thinking,
			StreamOptions options) {
		partial.content.add(new ThinkingContent("", thinking.thinkingSignature(), thinking.redacted()));
		stream.push(new AssistantMessageEvent.ThinkingStart(index, partial));
		StringBuilder value = new StringBuilder();
		for (String chunk : chunks(thinking.thinking())) {
			if (options.isAborted()) {
				return;
			}
			value.append(chunk);
			partial.content.set(index, new ThinkingContent(value.toString(), thinking.thinkingSignature(), thinking.redacted()));
			stream.push(new AssistantMessageEvent.ThinkingDelta(index, chunk, partial));
		}
		stream.push(new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking(), partial));
	}

	private static void emitToolCall(
			AssistantMessageEventStream stream, AssistantMessage partial, int index, ToolCall call, StreamOptions options) {
		partial.content.add(new ToolCall(call.id(), call.name(), Json.object(), call.thoughtSignature()));
		stream.push(new AssistantMessageEvent.ToolCallStart(index, partial));
		String encoded = call.arguments().toString();
		for (String chunk : chunks(encoded)) {
			if (options.isAborted()) {
				return;
			}
			stream.push(new AssistantMessageEvent.ToolCallDelta(index, chunk, partial));
		}
		partial.content.set(index, call);
		stream.push(new AssistantMessageEvent.ToolCallEnd(index, call, partial));
	}

	private static List<String> chunks(String value) {
		List<String> chunks = new ArrayList<>();
		for (int start = 0; start < value.length(); start += 4) {
			chunks.add(value.substring(start, Math.min(value.length(), start + 4)));
		}
		return chunks.isEmpty() ? List.of("") : chunks;
	}

	private static void abort(AssistantMessageEventStream stream, AssistantMessage partial) {
		partial.stopReason = StopReason.ABORTED;
		partial.errorMessage = "Request was aborted";
		stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, partial));
	}

	private static void copyTerminalFields(AssistantMessage source, AssistantMessage target) {
		target.usage = source.usage;
		target.stopReason = source.stopReason;
		target.errorMessage = source.errorMessage;
		target.rawStopReason = source.rawStopReason;
		target.responseId = source.responseId;
		target.responseModel = source.responseModel;
		target.timestamp = source.timestamp;
	}

	private static AssistantMessage errorMessage(Model model, String message) {
		AssistantMessage result = new AssistantMessage(model.api, model.provider, model.id);
		result.stopReason = StopReason.ERROR;
		result.errorMessage = message;
		return result;
	}

	private static void estimateUsage(AssistantMessage response, Context context) {
		long input = estimateTokens(context.systemPrompt);
		for (var message : context.messages) {
			input += estimateTokens(message.toString());
		}
		long output = estimateTokens(response.text()) + estimateTokens(response.thinking());
		response.usage.input = input;
		response.usage.output = output;
		response.usage.totalTokens = input + output;
		Models.calculateCost(
				Model.builder()
						.id(response.model)
						.api(response.api)
						.provider(response.provider)
						.baseUrl("http://localhost:0")
						.cost(ModelCost.FREE)
						.build(),
				response.usage);
	}

	private static long estimateTokens(String text) {
		return text == null || text.isEmpty() ? 0 : (text.length() + 3L) / 4L;
	}

	/** One scripted reply, literal or derived from the request context. */
	public sealed interface ResponseStep permits ResponseStep.Message, ResponseStep.Factory {
		AssistantMessage resolve(Request request) throws Exception;

		record Message(AssistantMessage response) implements ResponseStep {
			@Override
			public AssistantMessage resolve(Request request) {
				return copy(response);
			}
		}

		record Factory(Function<Request, AssistantMessage> factory) implements ResponseStep {
			@Override
			public AssistantMessage resolve(Request request) {
				return factory.apply(request);
			}
		}
	}

	/** Inputs visible to response factories. */
	public record Request(Context context, StreamOptions options, State state, Model model) {}

	/** Observable test state. */
	public static final class State {
		private int callCount;

		public synchronized int callCount() {
			return callCount;
		}
	}

	public static AssistantMessage text(String content) {
		AssistantMessage response = new AssistantMessage(DEFAULT_API, DEFAULT_PROVIDER, DEFAULT_MODEL_ID);
		response.content.add(new TextContent(content));
		response.stopReason = StopReason.STOP;
		return response;
	}

	public static AssistantMessage thinking(String content) {
		AssistantMessage response = new AssistantMessage(DEFAULT_API, DEFAULT_PROVIDER, DEFAULT_MODEL_ID);
		response.content.add(new ThinkingContent(content));
		response.stopReason = StopReason.STOP;
		return response;
	}

	public static AssistantMessage toolCall(String name, ObjectNode arguments) {
		AssistantMessage response = new AssistantMessage(DEFAULT_API, DEFAULT_PROVIDER, DEFAULT_MODEL_ID);
		response.content.add(new ToolCall("tool-call-1", name, arguments));
		response.stopReason = StopReason.TOOL_USE;
		return response;
	}

	private static AssistantMessage copy(AssistantMessage source) {
		AssistantMessage copy = new AssistantMessage(source.api, source.provider, source.model);
		copy.content.addAll(source.content);
		copy.responseModel = source.responseModel;
		copy.responseId = source.responseId;
		copy.stopReason = source.stopReason;
		copy.errorMessage = source.errorMessage;
		copy.rawStopReason = source.rawStopReason;
		copy.timestamp = source.timestamp;
		copy.usage = source.usage;
		return copy;
	}
}
