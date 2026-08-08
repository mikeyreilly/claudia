package works.earendil.pi.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import works.earendil.pi.ai.Models;
import works.earendil.pi.ai.Provider;
import works.earendil.pi.ai.StreamOptions;
import works.earendil.pi.ai.auth.EnvApiKeys;
import works.earendil.pi.ai.http.HttpTransport;
import works.earendil.pi.ai.http.SseReader;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.stream.AssistantMessageEventStream;
import works.earendil.pi.ai.types.AssistantContent;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.AssistantMessageEvent;
import works.earendil.pi.ai.types.Context;
import works.earendil.pi.ai.types.ImageContent;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.StopReason;
import works.earendil.pi.ai.types.TextContent;
import works.earendil.pi.ai.types.ThinkingContent;
import works.earendil.pi.ai.types.Tool;
import works.earendil.pi.ai.types.ToolCall;
import works.earendil.pi.ai.types.ToolResultMessage;
import works.earendil.pi.ai.types.UserContent;

/** Native-image-safe adapter for Anthropic's streaming Messages API. */
public final class AnthropicProvider implements Provider {
	private static final String API_VERSION = "2023-06-01";
	private final String id;
	private final String name;
	private final List<String> apiKeyEnvVars;
	private final boolean bearerAuthentication;
	private final List<Model> models;

	public AnthropicProvider(List<Model> models) {
		this(
				"anthropic",
				"Anthropic",
				models,
				List.of(
						EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV,
						EnvApiKeys.ANTHROPIC_OAUTH_TOKEN_ENV,
						EnvApiKeys.ANTHROPIC_API_KEY_ENV),
				false);
	}

	public AnthropicProvider(
			String id, String name, List<Model> models, List<String> apiKeyEnvVars, boolean bearerAuthentication) {
		this.id = id;
		this.name = name;
		this.models = List.copyOf(models);
		this.apiKeyEnvVars = List.copyOf(apiKeyEnvVars);
		this.bearerAuthentication = bearerAuthentication;
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
		return "anthropic-messages";
	}

	@Override
	public List<Model> models() {
		return models;
	}

	@Override
	public List<String> apiKeyEnvVars() {
		return apiKeyEnvVars;
	}

	@Override
	public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
		if (!model.api.equals(api())) {
			throw new IllegalArgumentException("Model " + model + " is not an Anthropic Messages model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> produce(stream, model, context, requestOptions));
		return stream;
	}

	private void produce(
			AssistantMessageEventStream stream, Model model, Context context, StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			Map<String, String> headers = requestHeaders(model, options);
			ObjectNode request = requestBody(model, context, options);
			try (HttpTransport.Response response = HttpTransport.postJson(
							baseUrl(model, options) + "/v1/messages",
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
		headers.putIfAbsent("anthropic-version", API_VERSION);
		if (headers.containsKey("authorization") || headers.containsKey("Authorization") || headers.containsKey("x-api-key")) {
			return headers;
		}
		String key = options.apiKey;
		if (!bearerAuthentication) {
			String bearer = System.getenv(EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV);
			if (bearer != null && !bearer.isBlank()) {
				headers.put("authorization", "Bearer " + bearer);
				return headers;
			}
		}
		if ((key == null || key.isBlank()) && !bearerAuthentication) {
			key = EnvApiKeys.resolveSystem("anthropic").orElse(null);
		}
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("No API key for provider: " + id);
		}
		headers.put(bearerAuthentication ? "Authorization" : "x-api-key", bearerAuthentication ? "Bearer " + key : key);
		return headers;
	}

	private static String baseUrl(Model model, StreamOptions options) {
		return options.baseUrl == null || options.baseUrl.isBlank() ? model.baseUrl : options.baseUrl;
	}

	private static ObjectNode requestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = Json.object();
		request.put("model", model.id);
		request.put("stream", true);
		request.put("max_tokens", options.maxTokens != null ? options.maxTokens : model.maxTokens);
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			request.put("system", context.systemPrompt);
		}
		if (options.temperature != null) {
			request.put("temperature", options.temperature);
		}
		if (options.reasoning != null && options.reasoning != works.earendil.pi.ai.types.ThinkingLevel.OFF) {
			ObjectNode thinking = request.putObject("thinking");
			thinking.put("type", "enabled");
			thinking.put("budget_tokens", Math.min(model.maxTokens, 16_000));
		}
		ArrayNode messages = request.putArray("messages");
		for (Message message : context.messages) {
			appendMessage(messages, message);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (Tool tool : context.tools) {
				ObjectNode target = tools.addObject();
				target.put("name", tool.name());
				target.put("description", tool.description());
				target.set("input_schema", tool.parameters());
			}
		}
		return request;
	}

	private static void appendMessage(ArrayNode messages, Message message) {
		switch (message) {
			case works.earendil.pi.ai.types.UserMessage user -> appendUser(messages.addObject().put("role", "user"), user.content());
			case AssistantMessage assistant -> appendAssistant(messages.addObject().put("role", "assistant"), assistant);
			case ToolResultMessage result -> {
				ObjectNode target = messages.addObject().put("role", "user");
				ArrayNode content = target.putArray("content");
				ObjectNode toolResult = content.addObject().put("type", "tool_result").put("tool_use_id", result.toolCallId());
				toolResult.put("is_error", result.isError());
				appendContent(toolResult.putArray("content"), result.content());
			}
		}
	}

	private static void appendUser(ObjectNode target, List<UserContent> content) {
		boolean onlyText = content.stream().allMatch(TextContent.class::isInstance);
		if (onlyText) {
			target.put(
					"content",
					content.stream().map(TextContent.class::cast).map(TextContent::text).reduce("", String::concat));
			return;
		}
		appendContent(target.putArray("content"), content);
	}

	private static void appendContent(ArrayNode target, List<? extends UserContent> content) {
		for (UserContent block : content) {
			if (block instanceof TextContent text) {
				target.addObject().put("type", "text").put("text", text.text());
			} else if (block instanceof ImageContent image) {
				ObjectNode source = target.addObject().put("type", "image").putObject("source");
				source.put("type", "base64");
				source.put("media_type", image.mimeType());
				source.put("data", image.data());
			}
		}
	}

	private static void appendAssistant(ObjectNode target, AssistantMessage message) {
		ArrayNode content = target.putArray("content");
		for (AssistantContent block : message.content) {
			if (block instanceof TextContent text) {
				content.addObject().put("type", "text").put("text", text.text());
			} else if (block instanceof ThinkingContent thinking) {
				ObjectNode targetThinking = content.addObject().put("type", "thinking").put("thinking", thinking.thinking());
				if (thinking.thinkingSignature() != null) {
					targetThinking.put("signature", thinking.thinkingSignature());
				}
			} else if (block instanceof ToolCall call) {
				content.addObject()
						.put("type", "tool_use")
						.put("id", call.id())
						.put("name", call.name())
						.set("input", call.arguments());
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
		Map<Integer, ToolCallAccumulator> tools = new LinkedHashMap<>();
		SseReader.SseEvent sse;
		while ((sse = reader.next()) != null) {
			if (options.isAborted()) {
				abort(stream, output);
				return;
			}
			JsonNode event = Json.MAPPER.readTree(sse.data());
			if (event == null) {
				continue;
			}
			switch (sse.event()) {
				case "message_start" -> readMessageStart(event, output);
				case "content_block_start" -> startContent(stream, output, event, tools);
				case "content_block_delta" -> contentDelta(stream, output, event, tools);
				case "content_block_stop" -> stopContent(stream, output, event, tools);
				case "message_delta" -> readMessageDelta(event, output);
				case "message_stop" -> {
					finishUnstoppedTools(stream, output, tools);
					if (output.stopReason == StopReason.PENDING) {
						output.stopReason = output.toolCalls().isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
					}
					Models.calculateCost(model, output.usage);
					stream.push(new AssistantMessageEvent.Done(output.stopReason, output));
					return;
				}
				case "error" -> throw new IOException(event.path("error").path("message").asText("Anthropic stream error"));
				default -> {
					// ping and unknown future events do not affect the public stream.
				}
			}
		}
		throw new IOException("Anthropic stream ended before message_stop");
	}

	private static void readMessageStart(JsonNode event, AssistantMessage output) {
		JsonNode message = event.path("message");
		output.responseId = message.path("id").asText(null);
		output.responseModel = message.path("model").asText(null);
		readUsage(message.path("usage"), output);
	}

	private static void startContent(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<Integer, ToolCallAccumulator> tools) {
		int index = event.path("index").asInt();
		JsonNode block = event.path("content_block");
		switch (block.path("type").asText()) {
			case "text" -> {
				ensureContentIndex(output, index, new TextContent(""));
				stream.push(new AssistantMessageEvent.TextStart(index, output));
			}
			case "thinking" -> {
				ensureContentIndex(output, index, new ThinkingContent("", block.path("signature").asText(null), false));
				stream.push(new AssistantMessageEvent.ThinkingStart(index, output));
			}
			case "tool_use" -> {
				ToolCallAccumulator tool = new ToolCallAccumulator(index);
				tool.id = block.path("id").asText();
				tool.name = block.path("name").asText();
				tools.put(index, tool);
				ensureContentIndex(output, index, new ToolCall(tool.id, tool.name, Json.object()));
				stream.push(new AssistantMessageEvent.ToolCallStart(index, output));
			}
			default -> {
				// Anthropic may introduce non-user-visible block types.
			}
		}
	}

	private static void contentDelta(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<Integer, ToolCallAccumulator> tools) {
		int index = event.path("index").asInt();
		JsonNode delta = event.path("delta");
		switch (delta.path("type").asText()) {
			case "text_delta" -> {
				TextContent current = (TextContent) output.content.get(index);
				String text = delta.path("text").asText();
				output.content.set(index, current.withText(current.text() + text));
				stream.push(new AssistantMessageEvent.TextDelta(index, text, output));
			}
			case "thinking_delta" -> {
				ThinkingContent current = (ThinkingContent) output.content.get(index);
				String thinking = delta.path("thinking").asText();
				output.content.set(index, current.withThinking(current.thinking() + thinking));
				stream.push(new AssistantMessageEvent.ThinkingDelta(index, thinking, output));
			}
			case "input_json_delta" -> {
				ToolCallAccumulator tool = tools.get(index);
				if (tool == null) {
					throw new IllegalStateException("Anthropic tool input delta without tool block at " + index);
				}
				String json = delta.path("partial_json").asText();
				tool.arguments.append(json);
				stream.push(new AssistantMessageEvent.ToolCallDelta(index, json, output));
			}
			default -> {
				// signature_delta and unknown types have no public event.
			}
		}
	}

	private static void stopContent(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<Integer, ToolCallAccumulator> tools)
			throws IOException {
		int index = event.path("index").asInt();
		AssistantContent content = output.content.get(index);
		if (content instanceof TextContent text) {
			stream.push(new AssistantMessageEvent.TextEnd(index, text.text(), output));
		} else if (content instanceof ThinkingContent thinking) {
			stream.push(new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking(), output));
		} else if (content instanceof ToolCall) {
			finishTool(stream, output, index, tools);
		}
	}

	private static void finishUnstoppedTools(
			AssistantMessageEventStream stream, AssistantMessage output, Map<Integer, ToolCallAccumulator> tools)
			throws IOException {
		for (int index : List.copyOf(tools.keySet())) {
			if (output.content.get(index) instanceof ToolCall) {
				finishTool(stream, output, index, tools);
			}
		}
	}

	private static void finishTool(
			AssistantMessageEventStream stream, AssistantMessage output, int index, Map<Integer, ToolCallAccumulator> tools)
			throws IOException {
		ToolCallAccumulator tool = tools.remove(index);
		if (tool == null) {
			return;
		}
		JsonNode parsed = Json.MAPPER.readTree(tool.arguments.toString());
		if (!(parsed instanceof ObjectNode arguments)) {
			throw new IOException("Anthropic tool input must be a JSON object");
		}
		ToolCall completed = new ToolCall(tool.id, tool.name, arguments);
		output.content.set(index, completed);
		stream.push(new AssistantMessageEvent.ToolCallEnd(index, completed, output));
	}

	private static void ensureContentIndex(AssistantMessage output, int index, AssistantContent value) {
		while (output.content.size() <= index) {
			output.content.add(new TextContent(""));
		}
		output.content.set(index, value);
	}

	private static void readMessageDelta(JsonNode event, AssistantMessage output) {
		JsonNode delta = event.path("delta");
		String reason = delta.path("stop_reason").asText("");
		output.rawStopReason = reason.isEmpty() ? output.rawStopReason : reason;
		if (!reason.isEmpty()) {
			output.stopReason = switch (reason) {
				case "max_tokens" -> StopReason.LENGTH;
				case "tool_use" -> StopReason.TOOL_USE;
				default -> StopReason.STOP;
			};
		}
		readUsage(event.path("usage"), output);
	}

	private static void readUsage(JsonNode usage, AssistantMessage output) {
		if (!usage.isObject()) {
			return;
		}
		if (usage.path("input_tokens").isIntegralNumber()) {
			output.usage.input = usage.path("input_tokens").asLong();
		}
		if (usage.path("output_tokens").isIntegralNumber()) {
			output.usage.output = usage.path("output_tokens").asLong();
		}
		if (usage.path("cache_read_input_tokens").isIntegralNumber()) {
			output.usage.cacheRead = usage.path("cache_read_input_tokens").asLong();
		}
		if (usage.path("cache_creation_input_tokens").isIntegralNumber()) {
			output.usage.cacheWrite = usage.path("cache_creation_input_tokens").asLong();
		}
		if (usage.path("cache_creation").path("ephemeral_1h_input_tokens").isIntegralNumber()) {
			output.usage.cacheWrite1h = usage.path("cache_creation").path("ephemeral_1h_input_tokens").asLong();
		}
		output.usage.totalTokens =
				output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite;
	}

	private static void abort(AssistantMessageEventStream stream, AssistantMessage output) {
		output.stopReason = StopReason.ABORTED;
		output.errorMessage = "Request was aborted";
		stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, output));
	}

	private static String displayError(Exception error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}

	private static final class ToolCallAccumulator {
		private final int index;
		private String id;
		private String name;
		private final StringBuilder arguments = new StringBuilder();

		private ToolCallAccumulator(int index) {
			this.index = index;
		}
	}
}
