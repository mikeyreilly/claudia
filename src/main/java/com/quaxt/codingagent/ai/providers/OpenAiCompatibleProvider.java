package com.quaxt.codingagent.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.quaxt.codingagent.ai.Models;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.EnvApiKeys;
import com.quaxt.codingagent.ai.http.HttpTransport;
import com.quaxt.codingagent.ai.http.SseReader;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantContent;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.Tool;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;

/**
 * OpenAI Chat Completions-compatible provider adapter. It supports OpenAI
 * compatible services such as local llama.cpp/vLLM deployments without an SDK,
 * using the JDK HTTP/SSE transport so it remains native-image friendly.
 *
 * <p>First-party OpenAI's bundled catalog primarily uses the Responses API;
 * {@link OpenAiResponsesProvider} handles those models. This adapter remains
 * useful for generic endpoints and models explicitly configured with
 * {@code api = "openai-completions"}.
 */
public final class OpenAiCompatibleProvider implements Provider {
	private final String id;
	private final String name;
	private final String baseUrl;
	private final List<Model> models;

	public OpenAiCompatibleProvider(String id, String name, String baseUrl, List<Model> models) {
		this.id = requireNonBlank(id, "id");
		this.name = requireNonBlank(name, "name");
		this.baseUrl = trimTrailingSlash(requireNonBlank(baseUrl, "baseUrl"));
		this.models = List.copyOf(models);
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String api() {
		return "openai-completions";
	}

	@Override
	public List<Model> models() {
		return models;
	}

	@Override
	public List<String> apiKeyEnvVars() {
		return id.equals("openai") ? List.of("OPENAI_API_KEY") : List.of();
	}

	@Override
	public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
		if (!model.api.equals(api())) {
			throw new IllegalArgumentException("Model " + model + " is not a Chat Completions model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> produce(stream, model, context, requestOptions));
		return stream;
	}

	private void produce(AssistantMessageEventStream stream, Model model, Context context, StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			Map<String, String> headers = requestHeaders(model, options);
			ObjectNode request = requestBody(model, context, options);
			try (HttpTransport.Response response = HttpTransport.postJson(
							baseUrl(model, options) + "/chat/completions",
							headers,
							Json.MAPPER.writeValueAsBytes(request),
							options.timeoutMs,
							options.signal);
					SseReader reader = new SseReader(response.body())) {
				stream.push(new AssistantMessageEvent.Start(output));
				readSse(stream, reader, model, output, options);
			}
		} catch (Exception e) {
			output.stopReason = options.isAborted() ? StopReason.ABORTED : StopReason.ERROR;
			output.errorMessage = options.isAborted() ? "Request was aborted" : displayError(e);
			stream.push(new AssistantMessageEvent.Error(output.stopReason, output));
		}
	}

	private Map<String, String> requestHeaders(Model model, StreamOptions options) {
		Map<String, String> headers = new LinkedHashMap<>(model.headers);
		headers.putAll(options.headers);
		String key = options.apiKey;
		if (key == null || key.isBlank()) {
			key = EnvApiKeys.resolveSystem(id).orElse(null);
		}
		if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
			if (key == null || key.isBlank()) {
				throw new IllegalStateException("No API key for provider: " + id);
			}
			headers.put("Authorization", "Bearer " + key);
		}
		return headers;
	}

	private static ObjectNode requestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = Json.object();
		request.put("model", model.id);
		request.put("stream", true);
		request.putObject("stream_options").put("include_usage", true);
		if (options.temperature != null) {
			request.put("temperature", options.temperature);
		}
		if (options.maxTokens != null) {
			request.put("max_completion_tokens", options.maxTokens);
		}
		String reasoning = Models.providerThinkingLevel(model, options.reasoning);
		if (reasoning != null) {
			request.put("reasoning_effort", reasoning);
		}
		ArrayNode messages = request.putArray("messages");
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			messages.addObject().put("role", "system").put("content", context.systemPrompt);
		}
		for (Message message : context.messages) {
			appendMessage(messages, message);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (Tool tool : context.tools) {
				ObjectNode function = tools.addObject().put("type", "function").putObject("function");
				function.put("name", tool.name());
				function.put("description", tool.description());
				function.set("parameters", tool.parameters());
			}
		}
		return request;
	}

	private static void appendMessage(ArrayNode messages, Message message) {
		switch (message) {
			case com.quaxt.codingagent.ai.types.UserMessage user -> {
				ObjectNode target = messages.addObject().put("role", "user");
				appendUserContent(target, user.content());
			}
			case AssistantMessage assistant -> appendAssistantMessage(messages, assistant);
			case ToolResultMessage toolResult -> messages.addObject()
					.put("role", "tool")
					.put("tool_call_id", toolResult.toolCallId())
					.put("content", toolResult.text());
		}
	}

	private static void appendUserContent(ObjectNode target, List<com.quaxt.codingagent.ai.types.UserContent> content) {
		boolean hasImage = content.stream().anyMatch(ImageContent.class::isInstance);
		if (!hasImage) {
			target.put("content", content.stream()
					.filter(TextContent.class::isInstance)
					.map(TextContent.class::cast)
					.map(TextContent::text)
					.reduce("", String::concat));
			return;
		}
		ArrayNode parts = target.putArray("content");
		for (var block : content) {
			if (block instanceof TextContent text) {
				parts.addObject().put("type", "text").put("text", text.text());
			} else if (block instanceof ImageContent image) {
				parts.addObject()
						.put("type", "image_url")
						.putObject("image_url")
						.put("url", "data:" + image.mimeType() + ";base64," + image.data());
			}
		}
	}

	private static void appendAssistantMessage(ArrayNode messages, AssistantMessage message) {
		ObjectNode target = messages.addObject().put("role", "assistant");
		String text = message.text();
		target.put("content", text.isEmpty() ? "" : text);
		ArrayNode toolCalls = null;
		for (AssistantContent block : message.content) {
			if (block instanceof ToolCall call) {
				if (toolCalls == null) {
					toolCalls = target.putArray("tool_calls");
				}
				ObjectNode function = toolCalls.addObject()
						.put("id", call.id())
						.put("type", "function")
						.putObject("function");
				function.put("name", call.name());
				function.put("arguments", call.arguments().toString());
			}
		}
	}

	private static void readSse(
			AssistantMessageEventStream stream,
			SseReader reader,
			Model model,
			AssistantMessage output,
			StreamOptions options)
			throws IOException {
		Map<Integer, ToolCallAccumulator> tools = new HashMap<>();
		SseReader.SseEvent event;
		while ((event = reader.next()) != null) {
			if (options.isAborted()) {
				output.stopReason = StopReason.ABORTED;
				output.errorMessage = "Request was aborted";
				stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, output));
				return;
			}
			if (event.data().equals("[DONE]")) {
				break;
			}
			JsonNode chunk = Json.MAPPER.readTree(event.data());
			if (chunk == null) {
				continue;
			}
			readUsage(chunk, output, model);
			JsonNode choices = chunk.path("choices");
			if (!choices.isArray() || choices.isEmpty()) {
				continue;
			}
			JsonNode choice = choices.get(0);
			JsonNode delta = choice.path("delta");
			if (!delta.isMissingNode()) {
				readContentDelta(stream, output, delta);
				readThinkingDelta(stream, output, delta);
				readToolCallDeltas(stream, output, delta.path("tool_calls"), tools);
			}
			if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
				output.rawStopReason = choice.path("finish_reason").asText();
				output.stopReason = switch (output.rawStopReason) {
					case "length" -> StopReason.LENGTH;
					case "tool_calls", "function_call" -> StopReason.TOOL_USE;
					default -> StopReason.STOP;
				};
			}
		}
		finishToolCalls(stream, output, tools);
		if (output.stopReason == StopReason.PENDING) {
			output.stopReason = output.toolCalls().isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
		}
		Models.calculateCost(model, output.usage);
		stream.push(new AssistantMessageEvent.Done(output.stopReason, output));
	}

	private static void readContentDelta(AssistantMessageEventStream stream, AssistantMessage output, JsonNode delta) {
		if (!delta.path("content").isTextual()) {
			return;
		}
		String text = delta.path("content").asText();
		int index = lastContentIndex(output, TextContent.class);
		if (index == -1) {
			index = output.content.size();
			output.content.add(new TextContent(""));
			stream.push(new AssistantMessageEvent.TextStart(index, output));
		}
		TextContent content = (TextContent) output.content.get(index);
		output.content.set(index, content.withText(content.text() + text));
		stream.push(new AssistantMessageEvent.TextDelta(index, text, output));
	}

	private static void readThinkingDelta(AssistantMessageEventStream stream, AssistantMessage output, JsonNode delta) {
		JsonNode value = delta.has("reasoning_content") ? delta.path("reasoning_content") : delta.path("reasoning");
		if (!value.isTextual()) {
			return;
		}
		String thinking = value.asText();
		int index = lastContentIndex(output, ThinkingContent.class);
		if (index == -1) {
			index = output.content.size();
			output.content.add(new ThinkingContent(""));
			stream.push(new AssistantMessageEvent.ThinkingStart(index, output));
		}
		ThinkingContent content = (ThinkingContent) output.content.get(index);
		output.content.set(index, content.withThinking(content.thinking() + thinking));
		stream.push(new AssistantMessageEvent.ThinkingDelta(index, thinking, output));
	}

	private static void readToolCallDeltas(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode deltas,
			Map<Integer, ToolCallAccumulator> tools) {
		if (!deltas.isArray()) {
			return;
		}
		for (JsonNode delta : deltas) {
			int wireIndex = delta.path("index").asInt();
			ToolCallAccumulator accumulator = tools.get(wireIndex);
			if (accumulator == null) {
				accumulator = new ToolCallAccumulator(output.content.size());
				tools.put(wireIndex, accumulator);
				output.content.add(new ToolCall("", "", Json.object()));
				stream.push(new AssistantMessageEvent.ToolCallStart(accumulator.contentIndex, output));
			}
			if (delta.path("id").isTextual()) {
				accumulator.id = delta.path("id").asText();
			}
			JsonNode function = delta.path("function");
			if (function.path("name").isTextual()) {
				accumulator.name += function.path("name").asText();
			}
			if (function.path("arguments").isTextual()) {
				String arguments = function.path("arguments").asText();
				accumulator.arguments.append(arguments);
				stream.push(new AssistantMessageEvent.ToolCallDelta(accumulator.contentIndex, arguments, output));
			}
		}
	}

	private static void finishToolCalls(
			AssistantMessageEventStream stream, AssistantMessage output, Map<Integer, ToolCallAccumulator> accumulators)
			throws IOException {
		for (ToolCallAccumulator accumulator : accumulators.values()) {
			JsonNode parsed = Json.MAPPER.readTree(accumulator.arguments.toString());
			if (!(parsed instanceof ObjectNode arguments)) {
				throw new IOException("OpenAI tool call arguments must be a JSON object");
			}
			ToolCall call = new ToolCall(accumulator.id, accumulator.name, arguments);
			output.content.set(accumulator.contentIndex, call);
			stream.push(new AssistantMessageEvent.ToolCallEnd(accumulator.contentIndex, call, output));
		}
	}

	private static int lastContentIndex(AssistantMessage output, Class<? extends AssistantContent> contentType) {
		for (int i = output.content.size() - 1; i >= 0; i--) {
			if (contentType.isInstance(output.content.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private static void readUsage(JsonNode chunk, AssistantMessage output, Model model) {
		JsonNode usage = chunk.path("usage");
		if (!usage.isObject()) {
			return;
		}
		output.usage.input = usage.path("prompt_tokens").asLong(output.usage.input);
		output.usage.output = usage.path("completion_tokens").asLong(output.usage.output);
		output.usage.totalTokens = usage.path("total_tokens").asLong(output.usage.input + output.usage.output);
		JsonNode details = usage.path("prompt_tokens_details");
		if (details.path("cached_tokens").isIntegralNumber()) {
			output.usage.cacheRead = details.path("cached_tokens").asLong();
			output.usage.input -= output.usage.cacheRead;
		}
	}

	private static String displayError(Exception error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}

	private static String requireNonBlank(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	private static String trimTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static String baseUrl(Model model, StreamOptions options) {
		return options.baseUrl == null || options.baseUrl.isBlank() ? model.baseUrl : trimTrailingSlash(options.baseUrl);
	}

	private static final class ToolCallAccumulator {
		private final int contentIndex;
		private String id = "";
		private String name = "";
		private final StringBuilder arguments = new StringBuilder();

		private ToolCallAccumulator(int contentIndex) {
			this.contentIndex = contentIndex;
		}
	}
}
