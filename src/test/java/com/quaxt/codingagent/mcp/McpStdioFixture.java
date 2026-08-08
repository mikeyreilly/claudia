package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.quaxt.codingagent.ai.json.Json;

/** Tiny MCP server process used by the stdio integration test. */
public final class McpStdioFixture {
	private McpStdioFixture() {}

	public static void main(String[] args) throws Exception {
		try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = input.readLine()) != null) {
				JsonNode request = Json.MAPPER.readTree(line);
				if (!request.path("method").isTextual() || !request.has("id")) continue;
				ObjectNode response = Json.object().put("jsonrpc", "2.0");
				response.set("id", request.get("id"));
				switch (request.path("method").asText()) {
					case "initialize" -> {
						ObjectNode result = response.putObject("result");
						result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
						result.putObject("capabilities").putObject("tools").put("listChanged", false);
						result.putObject("serverInfo").put("name", "fixture").put("version", "1");
					}
					case "tools/list" -> {
						ObjectNode tool = response.putObject("result").putArray("tools").addObject();
						tool.put("name", "echo").put("description", "Echo a value");
						ObjectNode schema = tool.putObject("inputSchema").put("type", "object");
						schema.putObject("properties").putObject("value").put("type", "string");
						schema.putArray("required").add("value");
					}
					case "tools/call" -> response.putObject("result")
							.putArray("content")
							.addObject()
							.put("type", "text")
							.put("text", request.path("params").path("arguments").path("value").asText());
					default -> response.putObject("error").put("code", -32601).put("message", "not found");
				}
				System.out.println(Json.MAPPER.writeValueAsString(response));
				System.out.flush();
			}
		}
	}
}
