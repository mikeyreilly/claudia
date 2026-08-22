package com.quaxt.codingagent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandSuggestionsTest {
	private static final List<String> COMMANDS = List.of(
			"/theme",
			"/models",
			"/help",
			"/logout",
			"/compact",
			"/login",
			"/details",
			"/exit");

	@Test
	void sortsCommandsAndScrollsAFourItemWindow() {
		CommandSuggestions suggestions = new CommandSuggestions(COMMANDS);

		assertEquals(
				List.of("/compact", "/details", "/exit", "/help"),
				suggestions.visibleCommands("/"));
		assertEquals("/compact", suggestions.selectedCommand("/"));

		for (int index = 0; index < 4; index++) suggestions.move("/", 1);

		assertEquals("/login", suggestions.selectedCommand("/"));
		assertEquals(
				List.of("/details", "/exit", "/help", "/login"),
				suggestions.visibleCommands("/"));
	}

	@Test
	void filtersByPrefixAndDismissesAfterInsertionUntilTheBufferChanges() {
		CommandSuggestions suggestions = new CommandSuggestions(COMMANDS);

		assertEquals(List.of("/login", "/logout"), suggestions.visibleCommands("/lo"));
		suggestions.move("/lo", 1);
		assertEquals("/logout", suggestions.accept("/lo"));
		assertTrue(suggestions.visibleCommands("/logout").isEmpty());

		assertEquals(List.of("/login", "/logout"), suggestions.visibleCommands("/log"));
		assertEquals(List.of("/models"), suggestions.visibleCommands("/mo"));
		assertTrue(suggestions.visibleCommands("hello /mo").isEmpty());
		assertTrue(suggestions.visibleCommands("/models now").isEmpty());
	}

	@Test
	void rendersASelectedCommandInsideAPanel() {
		CommandSuggestions suggestions = new CommandSuggestions(COMMANDS);

		List<String> lines = suggestions.render("/mo", 40, Theme.PLAIN);

		assertEquals(3, lines.size());
		assertTrue(lines.getFirst().startsWith("╭"));
		assertTrue(lines.get(1).contains("› /models"));
		assertTrue(lines.getLast().startsWith("╰"));
	}
}
