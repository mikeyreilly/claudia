package com.quaxt.codingagent.cli.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

class SettingsStoreTest {
	@TempDir Path tempDir;

	@Test
	void persistsModelThinkingLevelAndThemeBetweenInstances() throws Exception {
		Path path = tempDir.resolve("nested/settings.json");
		SettingsStore first = CodingAgentOperations.settingsStore(path);

		assertEquals(CodingAgentOperations.emptySettings(), CodingAgentOperations.loadSettings(first));
		CodingAgentOperations.setSettingsDefaultModelAndProvider(first, "github-copilot", "gpt-5.4");
		CodingAgentOperations.setSettingsDefaultThinkingLevel(first, ThinkingLevel.HIGH);
		CodingAgentOperations.setSettingsTheme(first, "light");
		CodingAgentOperations.setSettingsHideThinkingBlock(first, true);

		var loaded = CodingAgentOperations.loadSettings(CodingAgentOperations.settingsStore(path));
		assertEquals("github-copilot", loaded.defaultProvider);
		assertEquals("gpt-5.4", loaded.defaultModel);
		assertEquals(ThinkingLevel.HIGH, loaded.defaultThinkingLevel);
		assertEquals("light", loaded.theme);
		assertTrue(loaded.hideThinkingBlock);
	}

	@Test
	void preservesUnknownSettingsWhenUpdatingKnownValues() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, "{\"custom\":{\"enabled\":true},\"defaultModel\":\"old\"}\n");
		SettingsStore store = CodingAgentOperations.settingsStore(path);

		CodingAgentOperations.setSettingsDefaultModelAndProvider(store, "anthropic", "claude-haiku-4-5");

		var root = Json.MAPPER.readTree(Files.readString(path));
		assertEquals(true, root.path("custom").path("enabled").asBoolean());
		assertEquals("anthropic", root.path("defaultProvider").asText());
		assertEquals("claude-haiku-4-5", root.path("defaultModel").asText());
	}

	@Test
	void persistsMcpServerAndToolTogglesWithoutDiscardingOtherSettings() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, """
				{
				  "custom": {"enabled": true},
				  "mcp": {
				    "fixture": {
				      "type": "local",
				      "command": ["tool"],
				      "enabled": true,
				      "disabledTools": ["already-disabled"]
				    }
				  }
				}
				""");
		SettingsStore store = CodingAgentOperations.settingsStore(path);

		CodingAgentOperations.setSettingsMcpServerEnabled(store, "fixture", false);
		CodingAgentOperations.setSettingsMcpToolEnabled(store, "fixture", "echo", false);
		CodingAgentOperations.setSettingsMcpToolEnabled(store, "fixture", "already-disabled", true);

		var root = Json.MAPPER.readTree(Files.readString(path));
		assertTrue(root.path("custom").path("enabled").asBoolean());
		assertFalse(root.path("mcp").path("fixture").path("enabled").asBoolean());
		assertEquals(1, root.path("mcp").path("fixture").path("disabledTools").size());
		assertEquals("echo", root.path("mcp").path("fixture").path("disabledTools").get(0).asText());

		CodingAgentOperations.setSettingsMcpToolEnabled(store, "fixture", "echo", true);
		root = Json.MAPPER.readTree(Files.readString(path));
		assertFalse(root.path("mcp").path("fixture").has("disabledTools"));
	}

	@Test
	void supportsFilesContainingOnlyOneHalfOfTheModelSelection() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, "{\"defaultProvider\":\"openai\"}\n");

		var loaded = CodingAgentOperations.loadSettings(CodingAgentOperations.settingsStore(path));
		assertEquals("openai", loaded.defaultProvider);
		assertNull(loaded.defaultModel);
	}

	@Test
	void rejectsInvalidJsonAndSettingTypes() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, "{ invalid json");
		assertThrows(java.io.IOException.class, () -> CodingAgentOperations.loadSettings(CodingAgentOperations.settingsStore(path)));

		Files.writeString(path, "{\"defaultThinkingLevel\":42}\n");
		assertThrows(java.io.IOException.class, () -> CodingAgentOperations.loadSettings(CodingAgentOperations.settingsStore(path)));

		Files.writeString(path, "{\"hideThinkingBlock\":\"yes\"}\n");
		assertThrows(java.io.IOException.class, () -> CodingAgentOperations.loadSettings(CodingAgentOperations.settingsStore(path)));
	}
}
