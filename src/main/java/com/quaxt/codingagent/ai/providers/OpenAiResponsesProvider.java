package com.quaxt.codingagent.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.quaxt.codingagent.ai.Models;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.CredentialStore;
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
 * Streaming adapter for OpenAI's Responses API. It covers the generated
 * first-party OpenAI catalog; {@link OpenAiCompatibleProvider} handles the
 * separate Chat Completions wire protocol.
 */
public final class OpenAiResponsesProvider implements Provider {
	private final String id;
	private final String name;
	private final List<String> apiKeyEnvVars;
	private final List<Model> models;
	private final CredentialStore credentials;
	private final RequestProfile requestProfile;

	public OpenAiResponsesProvider(List<Model> models) {
		this("openai", "OpenAI", models, List.of("OPENAI_API_KEY"), null, RequestProfile.STANDARD);
	}

	public OpenAiResponsesProvider(List<Model> models, CredentialStore credentials) {
		this("openai", "OpenAI", models, List.of("OPENAI_API_KEY"), credentials, RequestProfile.STANDARD);
	}

	public OpenAiResponsesProvider(String id, String name, List<Model> models, List<String> apiKeyEnvVars) {
		this(id, name, models, apiKeyEnvVars, null, RequestProfile.STANDARD);
	}

	public OpenAiResponsesProvider(
			String id, String name, List<Model> models, List<String> apiKeyEnvVars, CredentialStore credentials) {
		this(id, name, models, apiKeyEnvVars, credentials, RequestProfile.STANDARD);
	}

	static OpenAiResponsesProvider codex(String id, String name, List<Model> models) {
		return new OpenAiResponsesProvider(id, name, models, List.of(), null, RequestProfile.CODEX);
	}

	private OpenAiResponsesProvider(
			String id,
			String name,
			List<Model> models,
			List<String> apiKeyEnvVars,
			CredentialStore credentials,
			RequestProfile requestProfile) {
		this.id = id;
		this.name = name;
		this.models = List.copyOf(models);
		this.apiKeyEnvVars = List.copyOf(apiKeyEnvVars);
		this.credentials = credentials;
		this.requestProfile = requestProfile;
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
		return "openai-responses";
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
			throw new IllegalArgumentException("Model " + model + " is not an OpenAI Responses model");
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
			ObjectNode request = requestBody(model, context, options);
			try (HttpTransport.Response response = HttpTransport.postJson(
							baseUrl(model, options) + "/responses",
							requestHeaders(model, options),
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

	private Map<String, String> requestHeaders(Model model, StreamOptions options) throws IOException {
		Map<String, String> headers = new LinkedHashMap<>(model.headers);
		headers.putAll(options.headers);
		if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
			String key = options.apiKey;
			if ((key == null || key.isBlank()) && credentials != null) {
				key = credentials.read(id)
						.filter(Credential.ApiKeyCredential.class::isInstance)
						.map(Credential.ApiKeyCredential.class::cast)
						.map(Credential.ApiKeyCredential::key)
						.orElse(null);
			}
			if (key == null || key.isBlank()) {
				key = EnvApiKeys.resolveSystem(id).orElse(null);
			}
			if (key == null || key.isBlank()) {
				throw new IllegalStateException("No API key for provider: " + id);
			}
			headers.put("Authorization", "Bearer " + key);
		}
		return headers;
	}

	private static String baseUrl(Model model, StreamOptions options) {
		return options.baseUrl == null || options.baseUrl.isBlank() ? model.baseUrl : options.baseUrl;
	}

	private ObjectNode requestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = Json.object();
		request.put("model", model.id);
		request.put("stream", true);
		request.put("store", false);
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			request.put("instructions", context.systemPrompt);
		} else if (requestProfile == RequestProfile.CODEX) {
			request.put("instructions", "You are a helpful assistant.");
		}
		if (requestProfile == RequestProfile.CODEX) {
			request.putObject("text").put("verbosity", "low");
			request.put("tool_choice", "auto");
			request.put("parallel_tool_calls", true);
			if (options.sessionId != null && !options.sessionId.isBlank()) {
				request.put("prompt_cache_key", options.sessionId);
			}
		}
		if (options.maxTokens != null) {
			request.put("max_output_tokens", Math.max(16, options.maxTokens));
		}
		if (options.temperature != null) {
			request.put("temperature", options.temperature);
		}
		String reasoning = Models.providerThinkingLevel(model, options.reasoning);
		if (reasoning != null) {
			String summary = requestProfile == RequestProfile.CODEX ? "detailed" : "auto";
			request.putObject("reasoning").put("effort", reasoning).put("summary", summary);
			request.putArray("include").add("reasoning.encrypted_content");
		}
		ArrayNode input = request.putArray("input");
		for (Message message : context.messages) {
			appendMessage(input, message);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (Tool tool : context.tools) {
				ObjectNode target = tools.addObject().put("type", "function");
				target.put("name", tool.name());
				target.put("description", tool.description());
				target.set("parameters", tool.parameters());
			}
		}
		return request;
	}

	private static void appendMessage(ArrayNode input, Message message) {
		switch (message) {
			case com.quaxt.codingagent.ai.types.UserMessage user -> {
				ObjectNode target = input.addObject().put("role", "user");
				ArrayNode content = target.putArray("content");
				for (var block : user.content()) {
					if (block instanceof TextContent text) {
						content.addObject().put("type", "input_text").put("text", text.text());
					} else if (block instanceof ImageContent image) {
						content.addObject()
								.put("type", "input_image")
								.put("image_url", "data:" + image.mimeType() + ";base64," + image.data());
					}
				}
			}
			case AssistantMessage assistant -> {
				ObjectNode target = null;
				ArrayNode content = null;
				for (AssistantContent block : assistant.content) {
					if (block instanceof ThinkingContent thinking && thinking.thinkingSignature() != null) {
						try {
							JsonNode reasoningItem = Json.MAPPER.readTree(thinking.thinkingSignature());
							if (reasoningItem != null
									&& reasoningItem.isObject()
									&& reasoningItem.path("type").asText().equals("reasoning")) {
								input.add(reasoningItem);
							}
						} catch (IOException ignored) {
							// Signatures from another provider are not OpenAI response items.
						}
					} else if (block instanceof TextContent text) {
						if (target == null) {
							target = input.addObject().put("role", "assistant");
							content = target.putArray("content");
						}
						content.addObject().put("type", "output_text").put("text", text.text());
					} else if (block instanceof ToolCall call) {
						ObjectNode function = input.addObject().put("type", "function_call");
						function.put("call_id", call.id());
						function.put("name", call.name());
						function.put("arguments", call.arguments().toString());
					}
				}
			}
			case ToolResultMessage result -> input.addObject()
					.put("type", "function_call_output")
					.put("call_id", result.toolCallId())
					.put("output", result.text());
		}
	}

	private static void readSse(
			AssistantMessageEventStream stream,
			SseReader reader,
			Model model,
			AssistantMessage output,
			StreamOptions options)
			throws IOException {
		Map<String, OutputItem> items = new HashMap<>();
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
			switch (event.path("type").asText()) {
				case "response.created" -> output.responseId = event.path("response").path("id").asText(null);
				case "response.output_item.added" -> startItem(stream, output, event, items);
				case "response.output_text.delta", "response.refusal.delta" -> textDelta(stream, output, event, items);
				case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
						thinkingDelta(stream, output, event, items);
				case "response.reasoning_summary_part.done" -> thinkingPartDone(stream, output, event, items);
				case "response.function_call_arguments.delta" -> toolDelta(stream, output, event, items);
				case "response.function_call_arguments.done" -> finishTool(stream, output, event, items);
				case "response.output_item.done" -> finishItem(stream, output, event, items);
				case "response.completed", "response.incomplete" -> {
					JsonNode response = event.path("response");
					readCompletion(response, model, output);
					finalizeReasoningSignatures(response.path("output"), output);
					finishRemainingItems(stream, output, items);
					stream.push(new AssistantMessageEvent.Done(output.stopReason, output));
					return;
				}
				case "response.failed", "error" ->
						throw new IOException(event.path("error").path("message").asText("OpenAI Responses stream error"));
				default -> {
					// Other emitted event types do not change the normalized stream.
				}
			}
		}
		throw new IOException("OpenAI Responses stream ended without completion");
	}

	private static void startItem(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items) {
		JsonNode item = event.path("item");
		String itemId = itemKey(event);
		String type = item.path("type").asText();
		int contentIndex = output.content.size();
		OutputItem outputItem = new OutputItem(contentIndex, type);
		items.put(itemId, outputItem);
		switch (type) {
			case "message" -> {
				output.content.add(new TextContent(""));
				stream.push(new AssistantMessageEvent.TextStart(contentIndex, output));
			}
			case "reasoning" -> {
				output.content.add(new ThinkingContent(""));
				stream.push(new AssistantMessageEvent.ThinkingStart(contentIndex, output));
			}
			case "function_call" -> {
				outputItem.callId = item.path("call_id").asText();
				outputItem.name = item.path("name").asText();
				output.content.add(new ToolCall(outputItem.callId, outputItem.name, Json.object()));
				stream.push(new AssistantMessageEvent.ToolCallStart(contentIndex, output));
			}
			default -> items.remove(itemId);
		}
	}

	private static void textDelta(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items) {
		OutputItem item = items.get(itemKey(event));
		if (item == null || !item.type.equals("message")) {
			return;
		}
		String delta = event.path("delta").asText();
		TextContent current = (TextContent) output.content.get(item.contentIndex);
		output.content.set(item.contentIndex, current.withText(current.text() + delta));
		stream.push(new AssistantMessageEvent.TextDelta(item.contentIndex, delta, output));
	}

	private static void thinkingDelta(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items) {
		appendThinkingDelta(stream, output, items.get(itemKey(event)), event.path("delta").asText());
	}

	private static void thinkingPartDone(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items) {
		OutputItem item = items.get(itemKey(event));
		if (item == null || !item.type.equals("reasoning")) {
			return;
		}
		ThinkingContent current = (ThinkingContent) output.content.get(item.contentIndex);
		if (!current.thinking().isEmpty() && !current.thinking().endsWith("\n\n")) {
			appendThinkingDelta(stream, output, item, "\n\n");
		}
	}

	private static void appendThinkingDelta(
			AssistantMessageEventStream stream, AssistantMessage output, OutputItem item, String delta) {
		if (item == null || !item.type.equals("reasoning") || delta.isEmpty()) {
			return;
		}
		ThinkingContent current = (ThinkingContent) output.content.get(item.contentIndex);
		output.content.set(item.contentIndex, current.withThinking(current.thinking() + delta));
		stream.push(new AssistantMessageEvent.ThinkingDelta(item.contentIndex, delta, output));
	}

	private static void toolDelta(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items) {
		OutputItem item = items.get(itemKey(event));
		if (item == null || !item.type.equals("function_call")) {
			return;
		}
		String delta = event.path("delta").asText();
		item.arguments.append(delta);
		stream.push(new AssistantMessageEvent.ToolCallDelta(item.contentIndex, delta, output));
	}

	private static void finishTool(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items)
			throws IOException {
		OutputItem item = items.remove(itemKey(event));
		if (item == null || !item.type.equals("function_call")) {
			return;
		}
		String rawArguments = event.path("arguments").asText(item.arguments.toString());
		JsonNode parsed = Json.MAPPER.readTree(rawArguments);
		if (!(parsed instanceof ObjectNode arguments)) {
			throw new IOException("OpenAI function call arguments must be a JSON object");
		}
		ToolCall call = new ToolCall(item.callId, item.name, arguments);
		output.content.set(item.contentIndex, call);
		stream.push(new AssistantMessageEvent.ToolCallEnd(item.contentIndex, call, output));
	}

	private static void finishItem(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode event, Map<String, OutputItem> items)
			throws IOException {
		String itemId = itemKey(event);
		OutputItem item = items.get(itemId);
		if (item == null) {
			return;
		}
		if (item.type.equals("message")) {
			TextContent text = (TextContent) output.content.get(item.contentIndex);
			stream.push(new AssistantMessageEvent.TextEnd(item.contentIndex, text.text(), output));
			items.remove(itemId);
		} else if (item.type.equals("reasoning")) {
			ThinkingContent thinking = (ThinkingContent) output.content.get(item.contentIndex);
			JsonNode completedItem = event.path("item");
			String completedThinking = reasoningText(completedItem);
			if (completedThinking.isBlank()) {
				completedThinking = thinking.thinking().stripTrailing();
			}
			String signature = completedItem.isObject() ? completedItem.toString() : thinking.thinkingSignature();
			thinking = new ThinkingContent(completedThinking, signature, thinking.redacted());
			output.content.set(item.contentIndex, thinking);
			stream.push(new AssistantMessageEvent.ThinkingEnd(item.contentIndex, thinking.thinking(), output));
			items.remove(itemId);
		} else if (item.type.equals("function_call")) {
			finishTool(stream, output, event, items);
		}
	}

	private static void finishRemainingItems(
			AssistantMessageEventStream stream, AssistantMessage output, Map<String, OutputItem> items) throws IOException {
		for (String itemId : List.copyOf(items.keySet())) {
			OutputItem item = items.get(itemId);
			if (item.type.equals("message")) {
				TextContent text = (TextContent) output.content.get(item.contentIndex);
				stream.push(new AssistantMessageEvent.TextEnd(item.contentIndex, text.text(), output));
				items.remove(itemId);
			} else if (item.type.equals("reasoning")) {
				ThinkingContent thinking = (ThinkingContent) output.content.get(item.contentIndex);
				stream.push(new AssistantMessageEvent.ThinkingEnd(item.contentIndex, thinking.thinking(), output));
				items.remove(itemId);
			} else if (item.type.equals("function_call")) {
				ObjectNode synthetic = Json.object().put("arguments", item.arguments.toString());
				if (itemId.startsWith("output:")) {
					synthetic.put("output_index", Integer.parseInt(itemId.substring("output:".length())));
				} else {
					synthetic.put("item_id", itemId.substring("item:".length()));
				}
				finishTool(stream, output, synthetic, items);
			}
		}
	}

	private static String itemKey(JsonNode event) {
		if (event.path("output_index").isIntegralNumber()) {
			return "output:" + event.path("output_index").asInt();
		}
		String itemId = event.path("item_id").asText(null);
		if (itemId == null || itemId.isBlank()) {
			itemId = event.path("item").path("id").asText(null);
		}
		if (itemId == null || itemId.isBlank()) {
			throw new IllegalArgumentException("OpenAI Responses event is missing output_index and item id");
		}
		return "item:" + itemId;
	}

	private static void finalizeReasoningSignatures(JsonNode responseItems, AssistantMessage output) {
		if (!responseItems.isArray()) return;
		for (JsonNode responseItem : responseItems) {
			if (!responseItem.path("type").asText().equals("reasoning")) continue;
			int fallbackIndex = -1;
			for (int index = 0; index < output.content.size(); index++) {
				if (!(output.content.get(index) instanceof ThinkingContent thinking)) continue;
				if (fallbackIndex < 0 && thinking.thinkingSignature() == null) fallbackIndex = index;
				if (signatureId(thinking.thinkingSignature()).equals(responseItem.path("id").asText())) {
					fallbackIndex = index;
					break;
				}
			}
			if (fallbackIndex < 0) continue;
			ThinkingContent thinking = (ThinkingContent) output.content.get(fallbackIndex);
			String text = reasoningText(responseItem);
			output.content.set(
					fallbackIndex,
					new ThinkingContent(
							text.isBlank() ? thinking.thinking() : text,
							responseItem.toString(),
							thinking.redacted()));
		}
	}

	private static String signatureId(String signature) {
		if (signature == null) return "";
		try {
			return Json.MAPPER.readTree(signature).path("id").asText();
		} catch (IOException ignored) {
			return "";
		}
	}

	private static String reasoningText(JsonNode item) {
		StringBuilder text = new StringBuilder();
		for (String field : List.of("summary", "content")) {
			JsonNode parts = item.path(field);
			if (!parts.isArray()) continue;
			for (JsonNode part : parts) {
				String value = part.path("text").asText();
				if (value.isBlank()) continue;
				if (!text.isEmpty()) text.append("\n\n");
				text.append(value);
			}
			if (!text.isEmpty()) break;
		}
		return text.toString();
	}

	private static void readCompletion(JsonNode response, Model model, AssistantMessage output) {
		output.responseId = response.path("id").asText(output.responseId);
		output.responseModel = response.path("model").asText(null);
		JsonNode usage = response.path("usage");
		output.usage.input = usage.path("input_tokens").asLong();
		output.usage.output = usage.path("output_tokens").asLong();
		output.usage.totalTokens = usage.path("total_tokens").asLong(output.usage.input + output.usage.output);
		output.usage.cacheRead = usage.path("input_tokens_details").path("cached_tokens").asLong();
		JsonNode reasoningTokens = usage.path("output_tokens_details").path("reasoning_tokens");
		if (reasoningTokens.isIntegralNumber()) {
			output.usage.reasoning = reasoningTokens.asLong();
		}
		output.usage.input -= output.usage.cacheRead;
		output.stopReason = output.toolCalls().isEmpty()
				? (response.path("status").asText().equals("incomplete") ? StopReason.LENGTH : StopReason.STOP)
				: StopReason.TOOL_USE;
		Models.calculateCost(model, output.usage);
	}

	private static void abort(AssistantMessageEventStream stream, AssistantMessage output) {
		output.stopReason = StopReason.ABORTED;
		output.errorMessage = "Request was aborted";
		stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, output));
	}

	private static String displayError(Exception error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}

	private enum RequestProfile {
		STANDARD,
		CODEX
	}

	private static final class OutputItem {
		private final int contentIndex;
		private final String type;
		private String callId;
		private String name;
		private final StringBuilder arguments = new StringBuilder();

		private OutputItem(int contentIndex, String type) {
			this.contentIndex = contentIndex;
			this.type = type;
		}
	}
}
