package com.quaxt.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.mcp.McpConfiguration;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.mcp.McpServerConfig;
import com.quaxt.codingagent.mcp.McpStdioFixture;
import com.quaxt.codingagent.tui.Theme;
import com.quaxt.codingagent.tui.TuiInput;

class McpSelectorTest {
	@TempDir Path tempDir;

	@Test
	void drillsIntoAConnectedServerAndTogglesItsTools() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		McpServerConfig.Local server = CodingAgentOperations.localMcpServerConfig(
				List.of(java, "-cp", System.getProperty("java.class.path"), McpStdioFixture.class.getName()),
				null,
				Map.of(),
				true,
				5_000L,
				List.of(),
				List.of());
		AtomicInteger changes = new AtomicInteger();
		List<McpSelector.Change> persisted = new ArrayList<>();
		McpManager manager = CodingAgentOperations.mcpCreateManager(
				CodingAgentOperations.mcpConfiguration(Map.of("fixture", server), List.of()), tempDir);
		try {
			CodingAgentOperations.mcpAwaitReady(manager);
			McpSelector selector = CodingAgentOperations.newMcpSelector(manager, change -> {
				changes.incrementAndGet();
				persisted.add(change);
			});

			assertTrue(String.join("\n", CodingAgentOperations.renderMcpSelector(selector, 100, 30, Theme.PLAIN)).contains("MCP Servers"));
			CodingAgentOperations.handleMcpSelectorInput(selector, CodingAgentOperations.key(TuiInput.KeyType.TAB));
			String tools = String.join("\n", CodingAgentOperations.renderMcpSelector(selector, 100, 30, Theme.PLAIN));
			assertTrue(tools.contains("MCP Tools: fixture"));
			assertTrue(tools.contains("echo  Enabled"));
			assertTrue(tools.contains("reverse  Enabled"));

			CodingAgentOperations.handleMcpSelectorInput(selector, CodingAgentOperations.key(TuiInput.KeyType.ENTER));

			assertFalse(CodingAgentOperations.mcpToolStatuses(manager, "fixture").getFirst().enabled);
			assertEquals(1, changes.get());
			assertTrue(String.join("\n", CodingAgentOperations.renderMcpSelector(selector, 100, 30, Theme.PLAIN)).contains("echo  Disabled"));

			CodingAgentOperations.handleMcpSelectorInput(selector, CodingAgentOperations.key(TuiInput.KeyType.ESCAPE));
			String servers = String.join("\n", CodingAgentOperations.renderMcpSelector(selector, 100, 30, Theme.PLAIN));
			assertTrue(servers.contains("MCP Servers"));
			assertTrue(servers.contains("Enabled · 1/2 tool(s)"));
			CodingAgentOperations.handleMcpSelectorInput(selector, CodingAgentOperations.key(TuiInput.KeyType.ENTER));
			assertEquals(McpManager.State.DISABLED, CodingAgentOperations.mcpStatus(manager, "fixture").state);
			assertEquals(2, changes.get());
			CodingAgentOperations.handleMcpSelectorInput(selector, CodingAgentOperations.key(TuiInput.KeyType.ESCAPE));
			assertTrue(selector.complete);
			assertEquals(
					List.of(
							new McpSelector.Change("fixture", "echo", false),
							new McpSelector.Change("fixture", null, false)),
					persisted);

			assertEquals(
					McpManager.State.CONNECTED, CodingAgentOperations.mcpConnectServer(manager, "fixture").state);
			McpSelector unsaved = CodingAgentOperations.newMcpSelector(manager, change -> {
				throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));
			});
			CodingAgentOperations.handleMcpSelectorInput(unsaved, CodingAgentOperations.key(TuiInput.KeyType.TAB));
			CodingAgentOperations.handleMcpSelectorInput(unsaved, CodingAgentOperations.key(TuiInput.KeyType.ENTER));
			assertTrue(String.join("\n", CodingAgentOperations.renderMcpSelector(unsaved, 100, 30, Theme.PLAIN))
					.contains("Change applied, but not saved: disk full"));
			assertTrue(CodingAgentOperations.mcpToolStatuses(manager, "fixture").getFirst().enabled);
		} finally {
			CodingAgentOperations.mcpCloseManager(manager);
		}
	}
}
