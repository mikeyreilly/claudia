package com.quaxt.codingagent.tui;

import com.quaxt.codingagent.CodingAgentCli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TuiInputReaderTest {
	@Test
	void parsesNavigationControlAndMouseSequences() {
		assertEquals("escape", Keybindings.DEFAULT_APP_KEYBINDINGS.get("interrupt"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.ESCAPE),
				CodingAgentCli.parseInputSequence("\u001b"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.CANCEL),
				CodingAgentCli.parseInputSequence("\u0003"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.UP),
				CodingAgentCli.parseInputSequence("\u001b[A"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.PAGE_DOWN),
				CodingAgentCli.parseInputSequence("\u001b[6~"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.SUSPEND),
				CodingAgentCli.parseInputSequence("\u001a"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.EXPAND_TOOLS),
				CodingAgentCli.parseInputSequence("\u000f"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.TOGGLE_THINKING),
				CodingAgentCli.parseInputSequence("\u0014"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.EXIT),
				CodingAgentCli.parseInputSequence("\u0004"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.PRESS, 0, 12, 7),
				CodingAgentCli.parseInputSequence("\u001b[<0;12;7M"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.SCROLL_DOWN, 1, 4, 9),
				CodingAgentCli.parseInputSequence("\u001b[<65;4;9M"));
	}
}
