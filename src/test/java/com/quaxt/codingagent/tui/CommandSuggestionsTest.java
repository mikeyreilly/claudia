package com.quaxt.codingagent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import com.quaxt.codingagent.CodingAgentOperations;
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
		CommandSuggestions suggestions = CodingAgentOperations.newCommandSuggestions(COMMANDS);

		assertEquals(
				List.of("/compact", "/details", "/exit", "/help"),
				CodingAgentOperations.visibleCommands(suggestions, "/"));
		assertEquals("/compact", CodingAgentOperations.selectedCommand(suggestions, "/"));

		for (int index = 0; index < 4; index++) CodingAgentOperations.moveCommandSuggestion(
				suggestions, "/", 1);

		assertEquals("/login", CodingAgentOperations.selectedCommand(suggestions, "/"));
		assertEquals(
				List.of("/details", "/exit", "/help", "/login"),
				CodingAgentOperations.visibleCommands(suggestions, "/"));
	}

	@Test
	void filtersByPrefixAndDismissesAfterInsertionUntilTheBufferChanges() {
		CommandSuggestions suggestions = CodingAgentOperations.newCommandSuggestions(COMMANDS);

		assertEquals(List.of("/login", "/logout"), CodingAgentOperations.visibleCommands(suggestions, "/lo"));
		CodingAgentOperations.moveCommandSuggestion(suggestions, "/lo", 1);
		assertEquals("/logout", CodingAgentOperations.acceptCommandSuggestion(suggestions, "/lo"));
		assertTrue(CodingAgentOperations.visibleCommands(suggestions, "/logout").isEmpty());

		assertEquals(List.of("/login", "/logout"), CodingAgentOperations.visibleCommands(
				suggestions, "/log"));
		assertEquals(List.of("/models"), CodingAgentOperations.visibleCommands(suggestions, "/mo"));
		assertTrue(CodingAgentOperations.visibleCommands(suggestions, "hello /mo").isEmpty());
		assertTrue(CodingAgentOperations.visibleCommands(suggestions, "/models now").isEmpty());
	}

	@Test
	void rendersASelectedCommandInsideAPanel() {
		CommandSuggestions suggestions = CodingAgentOperations.newCommandSuggestions(COMMANDS);

		List<String> lines = CodingAgentOperations.renderCommandSuggestions(
				suggestions, "/mo", 40, Theme.PLAIN);

		assertEquals(3, lines.size());
		assertTrue(lines.getFirst().startsWith("╭"));
		assertTrue(lines.get(1).contains("› /models"));
		assertTrue(lines.getLast().startsWith("╰"));
	}
}
