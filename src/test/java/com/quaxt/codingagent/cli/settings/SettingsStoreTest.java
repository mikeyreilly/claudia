package com.quaxt.codingagent.cli.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

class SettingsStoreTest {
	@TempDir Path tempDir;

	@Test
	void persistsModelThinkingLevelAndThemeBetweenInstances() throws Exception {
		Path path = tempDir.resolve("nested/settings.json");
		SettingsStore first = new SettingsStore(path);

		assertEquals(SettingsStore.Settings.empty(), first.load());
		first.setDefaultModelAndProvider("github-copilot", "gpt-5.4");
		first.setDefaultThinkingLevel(ThinkingLevel.HIGH);
		first.setTheme("light");
		first.setHideThinkingBlock(true);

		var loaded = new SettingsStore(path).load();
		assertEquals("github-copilot", loaded.defaultProvider());
		assertEquals("gpt-5.4", loaded.defaultModel());
		assertEquals(ThinkingLevel.HIGH, loaded.defaultThinkingLevel());
		assertEquals("light", loaded.theme());
		assertTrue(loaded.hideThinkingBlock());
	}

	@Test
	void preservesUnknownSettingsWhenUpdatingKnownValues() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, "{\"custom\":{\"enabled\":true},\"defaultModel\":\"old\"}\n");
		SettingsStore store = new SettingsStore(path);

		store.setDefaultModelAndProvider("anthropic", "claude-haiku-4-5");

		var root = Json.MAPPER.readTree(Files.readString(path));
		assertEquals(true, root.path("custom").path("enabled").asBoolean());
		assertEquals("anthropic", root.path("defaultProvider").asText());
		assertEquals("claude-haiku-4-5", root.path("defaultModel").asText());
	}

	@Test
	void supportsFilesContainingOnlyOneHalfOfTheModelSelection() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, "{\"defaultProvider\":\"openai\"}\n");

		var loaded = new SettingsStore(path).load();
		assertEquals("openai", loaded.defaultProvider());
		assertNull(loaded.defaultModel());
	}

	@Test
	void rejectsInvalidJsonAndSettingTypes() throws Exception {
		Path path = tempDir.resolve("settings.json");
		Files.writeString(path, "{ invalid json");
		assertThrows(java.io.IOException.class, () -> new SettingsStore(path).load());

		Files.writeString(path, "{\"defaultThinkingLevel\":42}\n");
		assertThrows(java.io.IOException.class, () -> new SettingsStore(path).load());

		Files.writeString(path, "{\"hideThinkingBlock\":\"yes\"}\n");
		assertThrows(java.io.IOException.class, () -> new SettingsStore(path).load());
	}
}
