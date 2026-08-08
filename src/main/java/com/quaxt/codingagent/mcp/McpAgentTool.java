package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.UserContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** Adapter that exposes one remote MCP tool to the coding-agent loop. */
public final class McpAgentTool implements AgentTool {
	private final String serverName;
	private final McpClient.ToolDefinition definition;
	private final McpClient client;
	private final String name;

	McpAgentTool(String serverName, McpClient.ToolDefinition definition, McpClient client) {
		this.serverName = serverName;
		this.definition = definition;
		this.client = client;
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
		List<UserContent> content = convertContent(result.raw());
		if (content.isEmpty()) {
			content = List.of(new TextContent(result.isError() ? "MCP tool returned an error" : ""));
		}
		return new ToolResult(content, result.raw(), result.isError());
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
