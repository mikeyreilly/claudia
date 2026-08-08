package works.earendil.pi.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/** Native REST/SSE adapter for the Google Generative Language (Gemini) API. */
public final class GoogleProvider implements Provider {
	private final List<Model> models;

	public GoogleProvider(List<Model> models) {
		this.models = List.copyOf(models);
	}

	@Override
	public String id() {
		return "google";
	}

	@Override
	public String name() {
		return "Google";
	}

	@Override
	public String api() {
		return "google-generative-ai";
	}

	@Override
	public List<Model> models() {
		return models;
	}

	@Override
	public List<String> apiKeyEnvVars() {
		return List.of("GEMINI_API_KEY", "GOOGLE_API_KEY");
	}

	@Override
	public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
		if (!model.api.equals(api())) {
			throw new IllegalArgumentException("Model " + model + " is not a Google Generative AI model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> produce(stream, model, context, requestOptions));
		return stream;
	}

	private static void produce(
			AssistantMessageEventStream stream, Model model, Context context, StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			String key = options.apiKey;
			if (key == null || key.isBlank()) {
				key = EnvApiKeys.resolveSystem("google").orElse(null);
			}
			if (key == null || key.isBlank()) {
				throw new IllegalStateException("No API key for provider: google");
			}
			String url = model.baseUrl + "/models/" + encodePath(model.id) + ":streamGenerateContent?alt=sse&key="
					+ URLEncoder.encode(key, StandardCharsets.UTF_8);
			Map<String, String> headers = new LinkedHashMap<>(model.headers);
			headers.putAll(options.headers);
			try (HttpTransport.Response response = HttpTransport.postJson(
							url,
							headers,
							Json.MAPPER.writeValueAsBytes(requestBody(model, context, options)),
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

	private static ObjectNode requestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = Json.object();
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			request.putObject("systemInstruction").putArray("parts").addObject().put("text", context.systemPrompt);
		}
		ArrayNode contents = request.putArray("contents");
		for (Message message : context.messages) {
			appendMessage(contents, message);
		}
		ObjectNode generation = request.putObject("generationConfig");
		if (options.temperature != null) {
			generation.put("temperature", options.temperature);
		}
		if (options.maxTokens != null) {
			generation.put("maxOutputTokens", options.maxTokens);
		}
		if (options.reasoning != null && options.reasoning != works.earendil.pi.ai.types.ThinkingLevel.OFF) {
			generation.putObject("thinkingConfig").put("includeThoughts", true);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode declarations = request.putArray("tools").addObject().putArray("functionDeclarations");
			for (Tool tool : context.tools) {
				ObjectNode declaration = declarations.addObject();
				declaration.put("name", tool.name());
				declaration.put("description", tool.description());
				declaration.set("parametersJsonSchema", tool.parameters());
			}
		}
		return request;
	}

	private static void appendMessage(ArrayNode contents, Message message) {
		switch (message) {
			case works.earendil.pi.ai.types.UserMessage user -> appendUser(contents.addObject().put("role", "user"), user.content());
			case AssistantMessage assistant -> {
				ArrayNode parts = contents.addObject().put("role", "model").putArray("parts");
				for (AssistantContent block : assistant.content) {
					if (block instanceof TextContent text) {
						parts.addObject().put("text", text.text());
					} else if (block instanceof ThinkingContent thinking) {
						ObjectNode part = parts.addObject().put("text", thinking.thinking()).put("thought", true);
						if (thinking.thinkingSignature() != null) {
							part.put("thoughtSignature", thinking.thinkingSignature());
						}
					} else if (block instanceof ToolCall call) {
						ObjectNode function = parts.addObject().putObject("functionCall");
						function.put("name", call.name());
						function.set("args", call.arguments());
						function.put("id", call.id());
					}
				}
			}
			case ToolResultMessage result -> {
				ObjectNode function = contents.addObject()
						.put("role", "user")
						.putArray("parts")
						.addObject()
						.putObject("functionResponse");
				function.put("name", result.toolName());
				function.put("id", result.toolCallId());
				function.putObject("response").put(result.isError() ? "error" : "output", result.text());
			}
		}
	}

	private static void appendUser(ObjectNode target, List<UserContent> content) {
		ArrayNode parts = target.putArray("parts");
		for (UserContent block : content) {
			if (block instanceof TextContent text) {
				parts.addObject().put("text", text.text());
			} else if (block instanceof ImageContent image) {
				parts.addObject().putObject("inlineData").put("mimeType", image.mimeType()).put("data", image.data());
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
		SseReader.SseEvent sse;
		int toolCounter = 0;
		while ((sse = reader.next()) != null) {
			if (options.isAborted()) {
				abort(stream, output);
				return;
			}
			JsonNode chunk = Json.MAPPER.readTree(sse.data());
			if (chunk == null) {
				continue;
			}
			output.responseId = chunk.path("responseId").asText(output.responseId);
			JsonNode candidate = chunk.path("candidates").path(0);
			for (JsonNode part : candidate.path("content").path("parts")) {
				if (part.path("text").isTextual()) {
					String text = part.path("text").asText();
					if (part.path("thought").asBoolean()) {
						int index = ensureThinking(stream, output);
						ThinkingContent current = (ThinkingContent) output.content.get(index);
						output.content.set(
								index,
								new ThinkingContent(
										current.thinking() + text,
										part.path("thoughtSignature").asText(current.thinkingSignature()),
										false));
						stream.push(new AssistantMessageEvent.ThinkingDelta(index, text, output));
					} else {
						int index = ensureText(stream, output);
						TextContent current = (TextContent) output.content.get(index);
						output.content.set(
								index,
								new TextContent(current.text() + text, part.path("thoughtSignature").asText(current.textSignature())));
						stream.push(new AssistantMessageEvent.TextDelta(index, text, output));
					}
				}
				JsonNode function = part.path("functionCall");
				if (function.isObject()) {
					int index = output.content.size();
					String id = function.path("id").asText("call_" + (++toolCounter));
					String name = function.path("name").asText();
					JsonNode args = function.path("args");
					ObjectNode arguments = args instanceof ObjectNode object ? object : Json.object();
					ToolCall call = new ToolCall(id, name, arguments, part.path("thoughtSignature").asText(null));
					output.content.add(call);
					stream.push(new AssistantMessageEvent.ToolCallStart(index, output));
					stream.push(new AssistantMessageEvent.ToolCallDelta(index, arguments.toString(), output));
					stream.push(new AssistantMessageEvent.ToolCallEnd(index, call, output));
				}
			}
			readUsage(chunk.path("usageMetadata"), output);
			String finish = candidate.path("finishReason").asText("");
			if (!finish.isEmpty()) {
				output.rawStopReason = finish;
				output.stopReason = switch (finish) {
					case "MAX_TOKENS" -> StopReason.LENGTH;
					case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> StopReason.ERROR;
					default -> output.toolCalls().isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
				};
			}
		}
		closeOpenBlocks(stream, output);
		if (output.stopReason == StopReason.PENDING) {
			output.stopReason = output.toolCalls().isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
		}
		Models.calculateCost(model, output.usage);
		if (output.stopReason == StopReason.ERROR) {
			output.errorMessage = "Google response blocked: " + output.rawStopReason;
			stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, output));
		} else {
			stream.push(new AssistantMessageEvent.Done(output.stopReason, output));
		}
	}

	private static int ensureText(AssistantMessageEventStream stream, AssistantMessage output) {
		if (!output.content.isEmpty() && output.content.getLast() instanceof TextContent) {
			return output.content.size() - 1;
		}
		int index = output.content.size();
		output.content.add(new TextContent(""));
		stream.push(new AssistantMessageEvent.TextStart(index, output));
		return index;
	}

	private static int ensureThinking(AssistantMessageEventStream stream, AssistantMessage output) {
		if (!output.content.isEmpty() && output.content.getLast() instanceof ThinkingContent) {
			return output.content.size() - 1;
		}
		int index = output.content.size();
		output.content.add(new ThinkingContent(""));
		stream.push(new AssistantMessageEvent.ThinkingStart(index, output));
		return index;
	}

	private static void closeOpenBlocks(AssistantMessageEventStream stream, AssistantMessage output) {
		for (int index = 0; index < output.content.size(); index++) {
			AssistantContent block = output.content.get(index);
			if (block instanceof TextContent text) {
				stream.push(new AssistantMessageEvent.TextEnd(index, text.text(), output));
			} else if (block instanceof ThinkingContent thinking) {
				stream.push(new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking(), output));
			}
		}
	}

	private static void readUsage(JsonNode usage, AssistantMessage output) {
		if (!usage.isObject()) {
			return;
		}
		long cacheRead = usage.path("cachedContentTokenCount").asLong();
		output.usage.input = usage.path("promptTokenCount").asLong() - cacheRead;
		output.usage.output =
				usage.path("candidatesTokenCount").asLong() + usage.path("thoughtsTokenCount").asLong();
		output.usage.cacheRead = cacheRead;
		output.usage.reasoning = usage.path("thoughtsTokenCount").asLong();
		output.usage.totalTokens =
				usage.path("totalTokenCount").asLong(output.usage.input + output.usage.output + cacheRead);
	}

	private static void abort(AssistantMessageEventStream stream, AssistantMessage output) {
		output.stopReason = StopReason.ABORTED;
		output.errorMessage = "Request was aborted";
		stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, output));
	}

	private static String encodePath(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String displayError(Exception error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}
}
