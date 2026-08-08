package com.quaxt.codingagent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TuiInputReaderTest {
	@Test
	void parsesNavigationControlAndMouseSequences() {
		assertEquals(
				new TuiInput.Key(TuiInput.KeyType.UP),
				TuiInputReader.parseSequence("\u001b[A"));
		assertEquals(
				new TuiInput.Key(TuiInput.KeyType.PAGE_DOWN),
				TuiInputReader.parseSequence("\u001b[6~"));
		assertEquals(
				new TuiInput.Key(TuiInput.KeyType.SUSPEND),
				TuiInputReader.parseSequence("\u001a"));
		assertEquals(
				new TuiInput.Key(TuiInput.KeyType.EXPAND_TOOLS),
				TuiInputReader.parseSequence("\u000f"));
		assertEquals(
				new TuiInput.Key(TuiInput.KeyType.TOGGLE_THINKING),
				TuiInputReader.parseSequence("\u0014"));
		assertEquals(
				new TuiInput.Key(TuiInput.KeyType.EXIT),
				TuiInputReader.parseSequence("\u0004"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.PRESS, 0, 12, 7),
				TuiInputReader.parseSequence("\u001b[<0;12;7M"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.SCROLL_DOWN, 1, 4, 9),
				TuiInputReader.parseSequence("\u001b[<65;4;9M"));
	}
}
