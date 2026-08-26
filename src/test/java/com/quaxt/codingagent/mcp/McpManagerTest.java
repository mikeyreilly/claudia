package com.quaxt.codingagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

class McpManagerTest {
	@TempDir Path tempDir;

	@Test
	void connectsCallsAndTogglesAStdioServer() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		McpServerConfig.Local server = new McpServerConfig.Local(
				List.of(java, "-cp", System.getProperty("java.class.path"), McpStdioFixture.class.getName()),
				null,
				Map.of(),
				true,
				5_000L,
				List.of(new McpResultFilter("e*o", List.of("avatarUrls", "self"))),
				List.of("reverse"));
		try (McpManager manager = new McpManager(
				new McpConfiguration(Map.of("fixture", server), List.of()), tempDir)) {
			manager.awaitReady();

			assertEquals(McpManager.State.CONNECTED, manager.status("fixture").state());
			assertTrue(manager.isEnabled("fixture"));
			assertEquals(2, manager.status("fixture").toolCount());
			assertEquals(1, manager.status("fixture").enabledToolCount());
			assertEquals(
					List.of("echo", "reverse"),
					manager.toolStatuses("fixture").stream().map(McpManager.ToolStatus::name).toList());
			assertFalse(manager.toolStatuses("fixture").get(1).enabled());
			assertEquals(List.of("fixture_echo"), manager.tools().stream().map(AgentTool::name).toList());
			assertTrue(manager.toggleTool("fixture", "reverse").enabled());
			assertEquals(2, manager.status("fixture").enabledToolCount());
			AgentTool tool = manager.tools().getFirst();
			assertEquals("fixture_echo", tool.name());
			AgentTool.ToolResult result = tool.execute(
					"call-1", Json.object().put("value", "hello"), new AbortSignal(), ignored -> {});
			assertEquals("hello", ((TextContent) result.content().getFirst()).text());
			assertFalse(result.isError());

			String json = "{\"self\":\"root\",\"user\":{\"name\":\"Ada\",\"avatarUrls\":{\"small\":\"url\"}},"
					+ "\"items\":[{\"self\":\"nested\",\"value\":1}]}";
			AgentTool.ToolResult filtered = tool.execute(
					"call-2", Json.object().put("value", json), new AbortSignal(), ignored -> {});
			assertEquals(
					"{\"user\":{\"name\":\"Ada\"},\"items\":[{\"value\":1}]}",
					((TextContent) filtered.content().getFirst()).text());

			assertFalse(manager.toggleTool("fixture", "reverse").enabled());
			assertEquals(2, manager.status("fixture").toolCount());
			assertEquals(1, manager.status("fixture").enabledToolCount());
			assertEquals(List.of("fixture_echo"), manager.tools().stream().map(AgentTool::name).toList());

			assertEquals(McpManager.State.DISABLED, manager.disconnect("fixture").state());
			assertFalse(manager.isEnabled("fixture"));
			assertTrue(manager.tools().isEmpty());
			assertEquals(McpManager.State.CONNECTED, manager.connect("fixture").state());
			assertTrue(manager.isEnabled("fixture"));
			assertEquals(List.of("fixture_echo"), manager.tools().stream().map(AgentTool::name).toList());
			assertTrue(manager.toggleTool("fixture", "reverse").enabled());
			assertEquals(2, manager.status("fixture").enabledToolCount());
			assertEquals(
					List.of("fixture_echo", "fixture_reverse"),
					manager.tools().stream().map(AgentTool::name).toList());
		}
	}
}
