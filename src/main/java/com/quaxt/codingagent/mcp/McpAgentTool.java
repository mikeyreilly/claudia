package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.UserContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** Adapter that exposes one remote MCP tool to the coding-agent loop. */
public final class McpAgentTool implements AgentTool {
	private final String serverName;
	private final McpClient.ToolDefinition definition;
	private final McpClient client;
	private final List<McpResultFilter> resultFilters;
	private final String name;

	McpAgentTool(
			String serverName,
			McpClient.ToolDefinition definition,
			McpClient client,
			List<McpResultFilter> resultFilters) {
		this.serverName = serverName;
		this.definition = definition;
		this.client = client;
		this.resultFilters = List.copyOf(resultFilters);
		this.name = sanitize(serverName) + "_" + sanitize(definition.name());
	}

	public String serverName() {
		return serverName;
	}

	public String remoteName() {
		return definition.name();
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String description() {
		return definition.description();
	}

	@Override
	public ObjectNode parameters() {
		return definition.inputSchema().deepCopy();
	}

	@Override
	public ToolResult execute(
			String toolCallId,
			ObjectNode arguments,
			AbortSignal signal,
			Consumer<ToolResult> onUpdate)
			throws Exception {
		McpClient.CallResult result = client.callTool(definition.name(), arguments, signal);
		ObjectNode filtered = filterResult(definition.name(), result.raw(), resultFilters);
		List<UserContent> content = convertContent(filtered);
		if (content.isEmpty()) {
			content = List.of(new TextContent(result.isError() ? "MCP tool returned an error" : ""));
		}
		return new ToolResult(content, filtered, result.isError());
	}

	static ObjectNode filterResult(String toolName, ObjectNode result, List<McpResultFilter> filters) {
		Set<String> dropKeys = new LinkedHashSet<>();
		for (McpResultFilter filter : filters) {
			if (filter.matches(toolName)) dropKeys.addAll(filter.dropKeys());
		}
		if (dropKeys.isEmpty()) return result;
		ObjectNode filtered = result.deepCopy();
		filterNode(filtered, dropKeys);
		return filtered;
	}

	private static JsonNode filterNode(JsonNode node, Set<String> dropKeys) {
		if (node instanceof ObjectNode object) {
			List<String> names = new ArrayList<>();
			object.fieldNames().forEachRemaining(names::add);
			for (String name : names) {
				if (dropKeys.contains(name)) object.remove(name);
				else object.set(name, filterNode(object.get(name), dropKeys));
			}
			return object;
		}
		if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
			for (int index = 0; index < array.size(); index++) {
				array.set(index, filterNode(array.get(index), dropKeys));
			}
			return array;
		}
		if (!node.isTextual()) return node;
		String text = node.asText();
		String trimmed = text.trim();
		if (!((trimmed.startsWith("{") && trimmed.endsWith("}"))
				|| (trimmed.startsWith("[") && trimmed.endsWith("]")))) return node;
		try {
			JsonNode embedded = Json.MAPPER.readTree(text);
			if (embedded == null || (!embedded.isObject() && !embedded.isArray())) return node;
			return Json.MAPPER.getNodeFactory().textNode(filterNode(embedded, dropKeys).toString());
		} catch (IOException ignored) {
			return node;
		}
	}

	private static List<UserContent> convertContent(ObjectNode result) {
		List<UserContent> output = new ArrayList<>();
		JsonNode content = result.get("content");
		if (content != null && content.isArray()) {
			for (JsonNode item : content) appendContent(output, item);
		}
		if (output.isEmpty()) {
			JsonNode structured = result.get("structuredContent");
			if (structured != null && !structured.isNull()) output.add(new TextContent(structured.toString()));
		}
		return List.copyOf(output);
	}

	private static void appendContent(List<UserContent> output, JsonNode item) {
		if (!item.isObject()) {
			output.add(new TextContent(item.toString()));
			return;
		}
		switch (item.path("type").asText()) {
			case "text" -> output.add(new TextContent(item.path("text").asText("")));
			case "image" -> {
				if (item.path("data").isTextual() && item.path("mimeType").isTextual()) {
					output.add(new ImageContent(item.path("data").asText(), item.path("mimeType").asText()));
				} else output.add(new TextContent(item.toString()));
			}
			case "resource" -> appendResource(output, item.path("resource"));
			case "resource_link" -> {
				String label = item.path("name").isTextual() ? item.path("name").asText() : item.path("uri").asText("resource");
				output.add(new TextContent(label + ": " + item.path("uri").asText(item.toString())));
			}
			case "audio" -> output.add(new TextContent(
					"[MCP audio content: " + item.path("mimeType").asText("unknown type") + "]"));
			default -> output.add(new TextContent(item.toString()));
		}
	}

	private static void appendResource(List<UserContent> output, JsonNode resource) {
		if (!resource.isObject()) {
			output.add(new TextContent(resource.toString()));
			return;
		}
		if (resource.path("text").isTextual()) {
			output.add(new TextContent(resource.path("text").asText()));
			return;
		}
		if (resource.path("blob").isTextual()
				&& resource.path("mimeType").isTextual()
				&& resource.path("mimeType").asText().startsWith("image/")) {
			output.add(new ImageContent(resource.path("blob").asText(), resource.path("mimeType").asText()));
			return;
		}
		output.add(new TextContent(resource.toString()));
	}

	static String sanitize(String value) {
		return value.replaceAll("[^a-zA-Z0-9_-]", "_");
	}
}
