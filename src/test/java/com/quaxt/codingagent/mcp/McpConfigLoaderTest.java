package com.quaxt.codingagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigLoaderTest {
	@TempDir Path tempDir;

	@Test
	void loadsAndMergesOpenCodeJsoncWithVariables() throws Exception {
		Path home = tempDir.resolve("home");
		Path global = home.resolve(".config/opencode");
		Path project = tempDir.resolve("workspace/project");
		Files.createDirectories(global);
		Files.createDirectories(project);
		Files.writeString(global.resolve("secret.txt"), "secret-value\n");
		Files.writeString(global.resolve("opencode.jsonc"), """
				{
				  // The shape is intentionally identical to OpenCode.
				  "mcp": {
				    "local": {
				      "type": "local",
				      "command": ["tool", "--token", "{env:TOOL_TOKEN}"],
				      "environment": { "SECRET": "{file:secret.txt}" },
				      "enabled": true,
				    },
				    "remote": {
				      "type": "remote",
				      "url": "https://example.test/mcp",
				      "headers": { "Authorization": "Bearer {env:TOOL_TOKEN}" }
				    }
				  }
				}
				""");
		Files.writeString(project.resolve("opencode.json"), """
				{"mcp":{"local":{"enabled":false}}}
				""");

		McpConfiguration config = new McpConfigLoader(home, Map.of("TOOL_TOKEN", "abc123")).load(project);

		assertEquals(2, config.servers().size());
		McpServerConfig.Local local = assertInstanceOf(McpServerConfig.Local.class, config.servers().get("local"));
		assertEquals(java.util.List.of("tool", "--token", "abc123"), local.command());
		assertEquals("secret-value", local.environment().get("SECRET"));
		assertFalse(local.enabled());
		McpServerConfig.Remote remote = assertInstanceOf(McpServerConfig.Remote.class, config.servers().get("remote"));
		assertEquals("Bearer abc123", remote.headers().get("Authorization"));
		assertTrue(config.sources().contains(global.resolve("opencode.jsonc")));
		assertTrue(config.sources().contains(project.resolve("opencode.json")));
	}
}
