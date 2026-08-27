package com.quaxt.codingagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

class McpRemoteTest {
	@TempDir Path tempDir;

	@Test
	void connectsToAStreamableHttpServer() throws Exception {
		AtomicBoolean sawSession = new AtomicBoolean();
		AtomicBoolean sawProtocol = new AtomicBoolean();
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/mcp", exchange -> handle(exchange, sawSession, sawProtocol));
		http.start();
		try {
			var remote = CodingAgentOperations.remoteMcpServerConfig(
					java.net.URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp"),
					Map.of("X-Test", "yes"),
					null,
					true,
					5_000L,
					List.of(),
					List.of());
			McpManager manager = CodingAgentOperations.mcpCreateManager(
					CodingAgentOperations.mcpConfiguration(Map.of("remote", remote), List.of()), tempDir);
			try {
				CodingAgentOperations.mcpAwaitReady(manager);
				assertEquals(McpManager.State.CONNECTED, CodingAgentOperations.mcpStatus(manager, "remote").state);
				AgentTool tool = CodingAgentOperations.mcpTools(manager).getFirst();
				AgentTool.ToolResult result = CodingAgentOperations.executeTool(
						tool, "id", CodingAgentOperations.jsonObject().put("value", "over http"), new AbortSignal(), ignored -> {});
				assertEquals("over http", ((TextContent) result.content.getFirst()).text);
			} finally {
				CodingAgentOperations.mcpCloseManager(manager);
			}
			assertTrue(sawSession.get());
			assertTrue(sawProtocol.get());
		} finally {
			http.stop(0);
		}
	}

	private static void handle(HttpExchange exchange, AtomicBoolean sawSession, AtomicBoolean sawProtocol)
			throws IOException {
		try (exchange) {
			if (exchange.getRequestMethod().equals("DELETE")) {
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
			String method = request.path("method").asText();
			if (!method.equals("initialize")) {
				sawSession.set("session-1".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
				sawProtocol.set("2025-11-25".equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")));
			}
			if (!request.has("id")) {
				exchange.sendResponseHeaders(202, -1);
				return;
			}
			ObjectNode response = CodingAgentOperations.jsonObject().put("jsonrpc", "2.0");
			response.set("id", request.get("id"));
			switch (method) {
				case "initialize" -> {
					ObjectNode result = response.putObject("result");
					result.put("protocolVersion", "2025-11-25");
					result.putObject("capabilities").putObject("tools");
					exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
				}
				case "tools/list" -> {
					ObjectNode tool = response.putObject("result").putArray("tools").addObject();
					tool.put("name", "echo");
					tool.putObject("inputSchema").put("type", "object").putObject("properties");
				}
				case "tools/call" -> response.putObject("result")
						.putArray("content")
						.addObject()
						.put("type", "text")
						.put("text", request.path("params").path("arguments").path("value").asText());
				default -> response.putObject("error").put("code", -32601).put("message", "not found");
			}
			byte[] body = Json.MAPPER.writeValueAsBytes(response);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		}
	}
}
