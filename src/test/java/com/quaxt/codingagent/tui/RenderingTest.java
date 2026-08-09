package com.quaxt.codingagent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RenderingTest {
	@Test
	void rendersOnlyChangedTerminalLines() {
		AnsiRenderer renderer = new AnsiRenderer();
		assertTrue(renderer.render(List.of("one", "two")).contains("one"));
		String update = renderer.render(List.of("one", "three"));
		assertFalse(update.contains("one"));
		assertTrue(update.contains("three"));
		assertEquals(List.of("one", "three"), renderer.previousLines());
	}

	@Test
	void formatsHeadingsBoldTextAndInlineCode() {
		String output = MarkdownRenderer.render("## Heading\n**strong** and `code`");
		assertTrue(output.contains("\u001b[1m\u001b[36mHeading"));
		assertTrue(output.contains("\u001b[1mstrong\u001b[0m"));
		assertTrue(output.contains("\u001b[2mcode\u001b[0m"));
	}

	@Test
	void givesPromptLinesAContrastingBackground() {
		assertEquals(
				"\u001b[48;5;236m\u001b[K> first\u001b[0m\n\u001b[48;5;236m\u001b[Ksecond\u001b[0m",
				Theme.DARK.promptArea("> first\nsecond"));
		assertEquals("> first", Theme.PLAIN.promptArea("> first"));
	}

	@Test
	void measuresWrapsAndLinksTerminalText() {
		assertEquals(4, TerminalText.visibleWidth("A\u754cB"));
		assertEquals("ab...", TerminalText.truncatePlain("abcdefgh", 5));
		assertEquals("ab", TerminalText.truncatePlain("abcdefgh", 2));
		assertEquals(List.of("A\u754cB", "cd", "", "ef"), TerminalText.wrapPlain("A\u754cBcd\n\nef", 4));
		assertEquals(
				"\u001b]8;;https://example.test\u001b\\docs\u001b]8;;\u001b\\",
				TerminalText.hyperlink("docs", "https://example.test"));
	}
}
