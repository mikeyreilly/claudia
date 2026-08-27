package com.quaxt.codingagent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import com.quaxt.codingagent.CodingAgentOperations;
import org.junit.jupiter.api.Test;

class RenderingTest {
	@Test
	void rendersOnlyChangedTerminalLines() {
		AnsiRenderer renderer = new AnsiRenderer();
		assertTrue(CodingAgentOperations.renderAnsiFrame(renderer, List.of("one", "two")).contains("one"));
		String update = CodingAgentOperations.renderAnsiFrame(renderer, List.of("one", "three"));
		assertFalse(update.contains("one"));
		assertTrue(update.contains("three"));
		assertEquals(List.of("one", "three"), CodingAgentOperations.ansiPreviousLines(renderer));
	}

	@Test
	void formatsHeadingsBoldTextAndInlineCode() {
		String output = CodingAgentOperations.renderMarkdown("## Heading\n**strong** and `code`");
		assertTrue(output.contains("\u001b[1m\u001b[36mHeading"));
		assertTrue(output.contains("\u001b[1mstrong\u001b[0m"));
		assertTrue(output.contains("\u001b[2mcode\u001b[0m"));
	}

	@Test
	void givesPromptLinesAContrastingBackground() {
		assertEquals(
				"\u001b[48;5;236m\u001b[K> first\u001b[0m\n\u001b[48;5;236m\u001b[Ksecond\u001b[0m",
				CodingAgentOperations.promptArea(Theme.DARK, "> first\nsecond"));
		assertEquals("> first", CodingAgentOperations.promptArea(Theme.PLAIN, "> first"));
	}

	@Test
	void measuresWrapsAndLinksTerminalText() {
		assertEquals(4, CodingAgentOperations.visibleWidth("A\u754cB"));
		assertEquals("ab...", CodingAgentOperations.truncatePlain("abcdefgh", 5));
		assertEquals("ab", CodingAgentOperations.truncatePlain("abcdefgh", 2));
		assertEquals(List.of("A\u754cB", "cd", "", "ef"), CodingAgentOperations.wrapPlain(
				"A\u754cBcd\n\nef", 4));
		assertEquals(
				"\u001b]8;;https://example.test\u001b\\docs\u001b]8;;\u001b\\",
				CodingAgentOperations.hyperlink("docs", "https://example.test"));
	}
}
