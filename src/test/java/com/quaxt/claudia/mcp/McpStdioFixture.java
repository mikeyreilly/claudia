package com.quaxt.claudia.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.quaxt.claudia.ClaudiaOperations;
import com.quaxt.claudia.ai.json.Json;

/** Tiny MCP server process used by the stdio integration test. */
public final class McpStdioFixture {
	private McpStdioFixture() {}

	public static void main(String[] args) throws Exception {
		try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = input.readLine()) != null) {
				JsonNode request = Json.MAPPER.readTree(line);
				if (!request.path("method").isTextual() || !request.has("id")) continue;
				ObjectNode response = ClaudiaOperations.jsonObject().put("jsonrpc", "2.0");
				response.set("id", request.get("id"));
				switch (request.path("method").asText()) {
					case "initialize" -> {
						ObjectNode result = response.putObject("result");
						result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
						result.putObject("capabilities").putObject("tools").put("listChanged", false);
						result.putObject("serverInfo").put("name", "fixture").put("version", "1");
					}
					case "tools/list" -> {
						ArrayNode tools = response.putObject("result").putArray("tools");
						addTool(tools, "echo", "Echo a value");
						addTool(tools, "reverse", "Reverse a value");
					}
					case "tools/call" -> {
						String value = request.path("params").path("arguments").path("value").asText();
						String name = request.path("params").path("name").asText();
						String result = name.equals("reverse") ? new StringBuilder(value).reverse().toString() : value;
						response.putObject("result")
								.putArray("content")
								.addObject()
								.put("type", "text")
								.put("text", result);
					}
					default -> response.putObject("error").put("code", -32601).put("message", "not found");
				}
				System.out.println(Json.MAPPER.writeValueAsString(response));
				System.out.flush();
			}
		}
	}

	private static void addTool(ArrayNode tools, String name, String description) {
		ObjectNode tool = tools.addObject();
		tool.put("name", name).put("description", description);
		ObjectNode schema = tool.putObject("inputSchema").put("type", "object");
		schema.putObject("properties").putObject("value").put("type", "string");
		schema.putArray("required").add("value");
	}
}
