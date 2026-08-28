package com.quaxt.codingagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;

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
				      "headers": { "Authorization": "Bearer {env:TOOL_TOKEN}" },
				      "oauth": {
				        "clientId": "configured-client",
				        "scope": "read write",
				        "callbackPort": 23456
				      },
				      "resultFilters": [
				        { "tool": "get*", "dropKeys": ["avatarUrls", "self"] }
				      ],
				      "disabledTools": ["deleteIssue"]
				    }
				  }
				}
				""");

		McpConfiguration config = CodingAgentOperations.mcpLoadConfiguration(
				new McpConfigLoader(
						settingsPath.toAbsolutePath().normalize(), Map.of("TOOL_TOKEN", "abc123")));

		assertEquals(2, config.servers.size());
		McpServerConfig.Local local = assertInstanceOf(McpServerConfig.Local.class, config.servers.get("local"));
		assertEquals(java.util.List.of("tool", "--token", "abc123"), local.command);
		assertEquals("secret-value", local.environment.get("SECRET"));
		assertFalse(local.enabled);
		McpServerConfig.Remote remote = assertInstanceOf(McpServerConfig.Remote.class, config.servers.get("remote"));
		assertEquals("Bearer abc123", remote.headers.get("Authorization"));
		assertEquals("configured-client", remote.oauth.path("clientId").asText());
		assertEquals("read write", remote.oauth.path("scope").asText());
		assertEquals(23456, remote.oauth.path("callbackPort").asInt());
		assertEquals("get*", remote.resultFilters.getFirst().tool);
		assertEquals(java.util.List.of("avatarUrls", "self"), remote.resultFilters.getFirst().dropKeys);
		assertEquals(java.util.List.of("deleteIssue"), remote.disabledTools);
		assertEquals(java.util.List.of(settingsPath), config.sources);
	}

	@Test
	void rejectsInvalidDisabledTools() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Files.createDirectories(settingsPath.getParent());
		Files.writeString(settingsPath, """
				{"mcp":{"local":{"type":"local","command":["tool"],"disabledTools":[42]}}}
				""");

		Exception error = assertThrows(
				java.io.IOException.class,
			() -> CodingAgentOperations.mcpLoadConfiguration(
					new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.of())));
		assertTrue(error.getMessage().contains("disabledTools"));
	}

	@Test
	void rejectsANonLoopbackOAuthRedirect() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Files.createDirectories(settingsPath.getParent());
		Files.writeString(settingsPath, """
				{"mcp":{"remote":{"type":"remote","url":"https://example.test/mcp",
				"oauth":{"redirectUri":"https://attacker.test/callback"}}}}
				""");

		Exception error = assertThrows(
				java.io.IOException.class,
			() -> CodingAgentOperations.mcpLoadConfiguration(
					new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.of())));
		assertTrue(error.getMessage().contains("HTTP loopback URL"));
	}

	@Test
	void doesNotDiscoverOpenCodeOrProjectConfiguration() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Path projectConfig = tempDir.resolve("workspace/.opencode/opencode.json");
		Files.createDirectories(projectConfig.getParent());
		Files.writeString(projectConfig, """
				{"mcp":{"ignored":{"type":"local","command":["ignored"]}}}
				""");

		McpConfiguration config = CodingAgentOperations.mcpLoadConfiguration(
				new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.of()));

		assertTrue(config.servers.isEmpty());
		assertTrue(config.sources.isEmpty());
	}
}
