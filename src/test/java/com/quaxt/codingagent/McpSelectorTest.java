package com.quaxt.codingagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McpSelectorTest {
	@Test
	void startsAtTheFirstItemWhenTheServerListIsShorterThanTheViewport() {
		assertEquals(0, CodingAgentOperations.mcpSelectorVisibleStart(1, 10, 0));
	}

	@Test
	void keepsTheSelectionCenteredWithinAFullViewport() {
		assertEquals(5, CodingAgentOperations.mcpSelectorVisibleStart(20, 10, 10));
		assertEquals(10, CodingAgentOperations.mcpSelectorVisibleStart(20, 10, 19));
	}
}
