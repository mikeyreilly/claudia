package com.quaxt.codingagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

class McpManagerTest {
	@TempDir Path tempDir;

	@Test
	void connectsCallsAndTogglesAStdioServer() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		McpServerConfig.Local server = CodingAgentOperations.localMcpServerConfig(
				List.of(java, "-cp", System.getProperty("java.class.path"), McpStdioFixture.class.getName()),
				null,
				Map.of(),
				true,
				5_000L,
				List.of(CodingAgentOperations.mcpResultFilter("e*o", List.of("avatarUrls", "self"))),
				List.of("reverse"));
		McpManager manager = CodingAgentOperations.mcpCreateManager(
				CodingAgentOperations.mcpConfiguration(Map.of("fixture", server), List.of()), tempDir);
		try {
			CodingAgentOperations.mcpAwaitReady(manager);

			assertEquals(McpManager.State.CONNECTED, CodingAgentOperations.mcpStatus(manager, "fixture").state);
			assertTrue(CodingAgentOperations.mcpIsEnabled(manager, "fixture"));
			assertEquals(2, CodingAgentOperations.mcpStatus(manager, "fixture").toolCount);
			assertEquals(1, CodingAgentOperations.mcpStatus(manager, "fixture").enabledToolCount);
			assertEquals(
					List.of("echo", "reverse"),
					CodingAgentOperations.mcpToolStatuses(manager, "fixture").stream().map((McpManager.ToolStatus status) -> status.name).toList());
			assertFalse(CodingAgentOperations.mcpToolStatuses(manager, "fixture").get(1).enabled);
			assertEquals(List.of("fixture_echo"), CodingAgentOperations.mcpTools(manager).stream().map(CodingAgentOperations::toolName).toList());
			assertTrue(CodingAgentOperations.mcpToggleTool(manager, "fixture", "reverse").enabled);
			assertEquals(2, CodingAgentOperations.mcpStatus(manager, "fixture").enabledToolCount);
			AgentTool tool = CodingAgentOperations.mcpTools(manager).getFirst();
			assertEquals("fixture_echo", CodingAgentOperations.toolName(tool));
			AgentTool.ToolResult result = CodingAgentOperations.executeTool(
					tool, "call-1", CodingAgentOperations.jsonObject().put("value", "hello"), new AbortSignal(), ignored -> {});
			assertEquals("hello", ((TextContent) result.content.getFirst()).text);
			assertFalse(result.isError);

			String json = "{\"self\":\"root\",\"user\":{\"name\":\"Ada\",\"avatarUrls\":{\"small\":\"url\"}},"
					+ "\"items\":[{\"self\":\"nested\",\"value\":1}]}";
			AgentTool.ToolResult filtered = CodingAgentOperations.executeTool(
					tool, "call-2", CodingAgentOperations.jsonObject().put("value", json), new AbortSignal(), ignored -> {});
			assertEquals(
					"{\"user\":{\"name\":\"Ada\"},\"items\":[{\"value\":1}]}",
					((TextContent) filtered.content.getFirst()).text);

			assertFalse(CodingAgentOperations.mcpToggleTool(manager, "fixture", "reverse").enabled);
			assertEquals(2, CodingAgentOperations.mcpStatus(manager, "fixture").toolCount);
			assertEquals(1, CodingAgentOperations.mcpStatus(manager, "fixture").enabledToolCount);
			assertEquals(List.of("fixture_echo"), CodingAgentOperations.mcpTools(manager).stream().map(CodingAgentOperations::toolName).toList());

			assertEquals(McpManager.State.DISABLED, CodingAgentOperations.mcpDisconnectServer(manager, "fixture").state);
			assertFalse(CodingAgentOperations.mcpIsEnabled(manager, "fixture"));
			assertTrue(CodingAgentOperations.mcpTools(manager).isEmpty());
			assertEquals(McpManager.State.CONNECTED, CodingAgentOperations.mcpConnectServer(manager, "fixture").state);
			assertTrue(CodingAgentOperations.mcpIsEnabled(manager, "fixture"));
			assertEquals(List.of("fixture_echo"), CodingAgentOperations.mcpTools(manager).stream().map(CodingAgentOperations::toolName).toList());
			assertTrue(CodingAgentOperations.mcpToggleTool(manager, "fixture", "reverse").enabled);
			assertEquals(2, CodingAgentOperations.mcpStatus(manager, "fixture").enabledToolCount);
			assertEquals(
					List.of("fixture_echo", "fixture_reverse"),
					CodingAgentOperations.mcpTools(manager).stream().map(CodingAgentOperations::toolName).toList());
		} finally {
			CodingAgentOperations.mcpCloseManager(manager);
		}
	}
}
