package com.quaxt.codingagent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quaxt.codingagent.CodingAgentOperations;
import org.junit.jupiter.api.Test;

class TuiInputReaderTest {
	@Test
	void parsesNavigationControlAndMouseSequences() {
		assertEquals("escape", Keybindings.DEFAULT_APP_KEYBINDINGS.get("interrupt"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.ESCAPE),
				CodingAgentOperations.parseInputSequence("\u001b"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.CANCEL),
				CodingAgentOperations.parseInputSequence("\u0003"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.UP),
				CodingAgentOperations.parseInputSequence("\u001b[A"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.PAGE_DOWN),
				CodingAgentOperations.parseInputSequence("\u001b[6~"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.SUSPEND),
				CodingAgentOperations.parseInputSequence("\u001a"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.EXPAND_TOOLS),
				CodingAgentOperations.parseInputSequence("\u000f"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.TOGGLE_THINKING),
				CodingAgentOperations.parseInputSequence("\u0014"));
		assertEquals(
				CodingAgentOperations.key(TuiInput.KeyType.EXIT),
				CodingAgentOperations.parseInputSequence("\u0004"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.PRESS, 0, 12, 7),
				CodingAgentOperations.parseInputSequence("\u001b[<0;12;7M"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.SCROLL_DOWN, 1, 4, 9),
				CodingAgentOperations.parseInputSequence("\u001b[<65;4;9M"));
	}
}
