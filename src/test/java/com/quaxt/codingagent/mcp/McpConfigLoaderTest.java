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
	void loadsMcpServersFromCodingAgentSettings() throws Exception {
		Path settingsDirectory = tempDir.resolve("home/.codingagent");
		Path settingsPath = settingsDirectory.resolve("settings.json");
		Files.createDirectories(settingsDirectory);
		Files.writeString(settingsDirectory.resolve("secret.txt"), "secret-value\n");
		Files.writeString(settingsPath, """
				{
				  "defaultModel": "some-model",
				  "mcp": {
				    "local": {
				      "type": "local",
				      "command": ["tool", "--token", "{env:TOOL_TOKEN}"],
				      "environment": { "SECRET": "{file:secret.txt}" },
				      "enabled": false
				    },
				    "remote": {
				      "type": "remote",
				      "url": "https://example.test/mcp",
				      "headers": { "Authorization": "Bearer {env:TOOL_TOKEN}" }
				    }
				  }
				}
				""");

		McpConfiguration config = new McpConfigLoader(settingsPath, Map.of("TOOL_TOKEN", "abc123")).load();

		assertEquals(2, config.servers().size());
		McpServerConfig.Local local = assertInstanceOf(McpServerConfig.Local.class, config.servers().get("local"));
		assertEquals(java.util.List.of("tool", "--token", "abc123"), local.command());
		assertEquals("secret-value", local.environment().get("SECRET"));
		assertFalse(local.enabled());
		McpServerConfig.Remote remote = assertInstanceOf(McpServerConfig.Remote.class, config.servers().get("remote"));
		assertEquals("Bearer abc123", remote.headers().get("Authorization"));
		assertEquals(java.util.List.of(settingsPath), config.sources());
	}

	@Test
	void doesNotDiscoverOpenCodeOrProjectConfiguration() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Path projectConfig = tempDir.resolve("workspace/.opencode/opencode.json");
		Files.createDirectories(projectConfig.getParent());
		Files.writeString(projectConfig, """
				{"mcp":{"ignored":{"type":"local","command":["ignored"]}}}
				""");

		McpConfiguration config = new McpConfigLoader(settingsPath, Map.of()).load();

		assertTrue(config.servers().isEmpty());
		assertTrue(config.sources().isEmpty());
	}
}
