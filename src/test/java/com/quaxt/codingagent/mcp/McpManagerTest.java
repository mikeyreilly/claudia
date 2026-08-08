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
				5_000L);
		try (McpManager manager = new McpManager(
				new McpConfiguration(Map.of("fixture", server), List.of()), tempDir)) {
			manager.awaitReady();

			assertEquals(McpManager.State.CONNECTED, manager.status("fixture").state());
			assertEquals(1, manager.status("fixture").toolCount());
			AgentTool tool = manager.tools().getFirst();
			assertEquals("fixture_echo", tool.name());
			AgentTool.ToolResult result = tool.execute(
					"call-1", Json.object().put("value", "hello"), new AbortSignal(), ignored -> {});
			assertEquals("hello", ((TextContent) result.content().getFirst()).text());
			assertFalse(result.isError());

			assertEquals(McpManager.State.DISABLED, manager.disconnect("fixture").state());
			assertTrue(manager.tools().isEmpty());
			assertEquals(McpManager.State.CONNECTED, manager.connect("fixture").state());
		}
	}
}
