package com.quaxt.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
		McpServerConfig.Local server = new McpServerConfig.Local(
				List.of(java, "-cp", System.getProperty("java.class.path"), McpStdioFixture.class.getName()),
				null,
				Map.of(),
				true,
				5_000L);
		AtomicInteger changes = new AtomicInteger();
		try (McpManager manager = new McpManager(new McpConfiguration(Map.of("fixture", server), List.of()), tempDir)) {
			manager.awaitReady();
			McpSelector selector = new McpSelector(manager, changes::incrementAndGet);

			assertTrue(String.join("\n", selector.render(100, 30, Theme.PLAIN)).contains("MCP Servers"));
			selector.handle(new TuiInput.Key(TuiInput.KeyType.TAB));
			String tools = String.join("\n", selector.render(100, 30, Theme.PLAIN));
			assertTrue(tools.contains("MCP Tools: fixture"));
			assertTrue(tools.contains("echo  Enabled"));
			assertTrue(tools.contains("reverse  Enabled"));

			selector.handle(new TuiInput.Key(TuiInput.KeyType.ENTER));

			assertFalse(manager.toolStatuses("fixture").getFirst().enabled());
			assertEquals(1, changes.get());
			assertTrue(String.join("\n", selector.render(100, 30, Theme.PLAIN)).contains("echo  Disabled"));

			selector.handle(new TuiInput.Key(TuiInput.KeyType.ESCAPE));
			String servers = String.join("\n", selector.render(100, 30, Theme.PLAIN));
			assertTrue(servers.contains("MCP Servers"));
			assertTrue(servers.contains("Enabled · 1/2 tool(s)"));
			selector.handle(new TuiInput.Key(TuiInput.KeyType.ESCAPE));
			assertTrue(selector.isComplete());
		}
	}
}
