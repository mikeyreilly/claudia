package works.earendil.pi.cli.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.types.AssistantContent;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.ImageContent;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.StopReason;
import works.earendil.pi.ai.types.TextContent;
import works.earendil.pi.ai.types.ThinkingContent;
import works.earendil.pi.ai.types.ToolCall;
import works.earendil.pi.ai.types.ToolResultMessage;
import works.earendil.pi.ai.types.UserContent;
import works.earendil.pi.ai.types.UserMessage;

/** JSON representation shared by session recording and restoration. */
final class SessionCodec {
	private SessionCodec() {}

	static ObjectNode encode(Message message) {
		ObjectNode node = Json.object();
		node.put("role", message.role());
		node.put("timestamp", message.timestamp());
		ArrayNode content = node.putArray("content");
		switch (message) {
			case UserMessage user -> encodeUserContent(content, user.content());
			case AssistantMessage assistant -> {
				putNullable(node, "api", assistant.api);
				putNullable(node, "provider", assistant.provider);
				putNullable(node, "model", assistant.model);
				putNullable(node, "responseModel", assistant.responseModel);
				putNullable(node, "responseId", assistant.responseId);
				node.put("stopReason", assistant.stopReason.wire());
				putNullable(node, "error", assistant.errorMessage);
				putNullable(node, "rawStopReason", assistant.rawStopReason);
				encodeUsage(node.putObject("usage"), assistant);
				for (AssistantContent block : assistant.content) {
					if (block instanceof TextContent text) {
						ObjectNode encoded = content.addObject().put("type", "text").put("text", text.text());
						putNullable(encoded, "textSignature", text.textSignature());
					} else if (block instanceof ThinkingContent thinking) {
						ObjectNode encoded = content.addObject()
								.put("type", "thinking")
								.put("text", thinking.thinking())
								.put("redacted", thinking.redacted());
						putNullable(encoded, "thinkingSignature", thinking.thinkingSignature());
					} else if (block instanceof ToolCall call) {
						ObjectNode encoded = content.addObject()
								.put("type", "toolCall")
								.put("id", call.id())
								.put("name", call.name());
						encoded.set("arguments", call.arguments());
						putNullable(encoded, "thoughtSignature", call.thoughtSignature());
					}
				}
			}
			case ToolResultMessage result -> {
				node.put("toolCallId", result.toolCallId());
				node.put("toolName", result.toolName());
				node.put("isError", result.isError());
				if (result.details() != null) {
					node.set("details", Json.MAPPER.valueToTree(result.details()));
				}
				encodeUserContent(content, result.content());
			}
		}
		return node;
	}

	static Message decode(JsonNode node) throws IOException {
		if (node == null || !node.isObject() || !node.path("role").isTextual()) {
			throw new IOException("Message payload must be an object with a string role");
		}
		long timestamp = node.path("timestamp").isIntegralNumber()
				? node.path("timestamp").asLong()
				: System.currentTimeMillis();
		return switch (node.path("role").asText()) {
			case "user" -> new UserMessage(decodeUserContent(node.get("content")), timestamp);
			case "assistant" -> decodeAssistant(node, timestamp);
			case "toolResult" -> new ToolResultMessage(
					requiredText(node, "toolCallId"),
					requiredText(node, "toolName"),
					decodeUserContent(node.get("content")),
					node.has("details") ? Json.MAPPER.treeToValue(node.get("details"), Object.class) : null,
					node.path("isError").asBoolean(false),
					timestamp);
			default -> throw new IOException("Unknown session message role: " + node.path("role").asText());
		};
	}

	private static AssistantMessage decodeAssistant(JsonNode node, long timestamp) throws IOException {
		AssistantMessage assistant = new AssistantMessage(
				optionalText(node, "api"), optionalText(node, "provider"), optionalText(node, "model"));
		assistant.timestamp = timestamp;
		assistant.responseModel = optionalText(node, "responseModel");
		assistant.responseId = optionalText(node, "responseId");
		assistant.errorMessage = optionalText(node, "error");
		assistant.rawStopReason = optionalText(node, "rawStopReason");
		String stopReason = optionalText(node, "stopReason");
		if (stopReason != null) {
			try {
				assistant.stopReason = StopReason.fromWire(stopReason);
			} catch (IllegalArgumentException error) {
				try {
					assistant.stopReason = StopReason.valueOf(stopReason.toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException ignored) {
					throw new IOException("Unknown assistant stop reason: " + stopReason, error);
				}
			}
		}
		decodeUsage(node.get("usage"), assistant);
		JsonNode content = node.get("content");
		if (content != null && !content.isArray()) {
			throw new IOException("Assistant message content must be an array");
		}
		if (content != null) {
			for (JsonNode block : content) {
				String type = requiredText(block, "type");
				switch (type) {
					case "text" -> assistant.content.add(new TextContent(
							requiredText(block, "text"), optionalText(block, "textSignature")));
					case "thinking" -> assistant.content.add(new ThinkingContent(
							requiredText(block, "text"),
							optionalText(block, "thinkingSignature"),
							block.path("redacted").asBoolean(false)));
					case "toolCall" -> {
						JsonNode arguments = block.get("arguments");
						if (!(arguments instanceof ObjectNode object)) {
							throw new IOException("Tool call arguments must be an object");
						}
						assistant.content.add(new ToolCall(
								requiredText(block, "id"),
								requiredText(block, "name"),
								object.deepCopy(),
								optionalText(block, "thoughtSignature")));
					}
					default -> throw new IOException("Unknown assistant content type: " + type);
				}
			}
		}
		return assistant;
	}

	private static void encodeUserContent(ArrayNode target, List<UserContent> source) {
		for (UserContent block : source) {
			if (block instanceof TextContent text) {
				ObjectNode encoded = target.addObject().put("type", "text").put("text", text.text());
				putNullable(encoded, "textSignature", text.textSignature());
			} else if (block instanceof ImageContent image) {
				target.addObject().put("type", "image").put("data", image.data()).put("mimeType", image.mimeType());
			}
		}
	}

	private static List<UserContent> decodeUserContent(JsonNode content) throws IOException {
		if (content == null || content.isNull()) {
			return List.of();
		}
		if (!content.isArray()) {
			throw new IOException("Message content must be an array");
		}
		List<UserContent> decoded = new ArrayList<>();
		for (JsonNode block : content) {
			String type = requiredText(block, "type");
			switch (type) {
				case "text" -> decoded.add(new TextContent(
						requiredText(block, "text"), optionalText(block, "textSignature")));
				case "image" -> decoded.add(new ImageContent(
						requiredText(block, "data"), requiredText(block, "mimeType")));
				default -> throw new IOException("Unknown user content type: " + type);
			}
		}
		return List.copyOf(decoded);
	}

	private static void encodeUsage(ObjectNode node, AssistantMessage assistant) {
		node.put("input", assistant.usage.input);
		node.put("output", assistant.usage.output);
		node.put("cacheRead", assistant.usage.cacheRead);
		node.put("cacheWrite", assistant.usage.cacheWrite);
		if (assistant.usage.cacheWrite1h != null) node.put("cacheWrite1h", assistant.usage.cacheWrite1h);
		if (assistant.usage.reasoning != null) node.put("reasoning", assistant.usage.reasoning);
		node.put("totalTokens", assistant.usage.totalTokens);
		ObjectNode cost = node.putObject("cost");
		cost.put("input", assistant.usage.cost.input);
		cost.put("output", assistant.usage.cost.output);
		cost.put("cacheRead", assistant.usage.cost.cacheRead);
		cost.put("cacheWrite", assistant.usage.cost.cacheWrite);
		cost.put("total", assistant.usage.cost.total);
	}

	private static void decodeUsage(JsonNode node, AssistantMessage assistant) {
		if (node == null || !node.isObject()) return;
		assistant.usage.input = node.path("input").asLong();
		assistant.usage.output = node.path("output").asLong();
		assistant.usage.cacheRead = node.path("cacheRead").asLong();
		assistant.usage.cacheWrite = node.path("cacheWrite").asLong();
		if (node.path("cacheWrite1h").isIntegralNumber()) {
			assistant.usage.cacheWrite1h = node.path("cacheWrite1h").asLong();
		}
		if (node.path("reasoning").isIntegralNumber()) {
			assistant.usage.reasoning = node.path("reasoning").asLong();
		}
		assistant.usage.totalTokens = node.path("totalTokens").asLong();
		JsonNode cost = node.get("cost");
		if (cost != null && cost.isObject()) {
			assistant.usage.cost.input = cost.path("input").asDouble();
			assistant.usage.cost.output = cost.path("output").asDouble();
			assistant.usage.cost.cacheRead = cost.path("cacheRead").asDouble();
			assistant.usage.cost.cacheWrite = cost.path("cacheWrite").asDouble();
			assistant.usage.cost.total = cost.path("total").asDouble();
		}
	}

	private static String requiredText(JsonNode node, String field) throws IOException {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || !value.isTextual()) {
			throw new IOException(field + " must be a string");
		}
		return value.asText();
	}

	private static String optionalText(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isTextual() ? value.asText() : null;
	}

	private static void putNullable(ObjectNode node, String field, String value) {
		if (value != null) node.put(field, value);
	}
}
