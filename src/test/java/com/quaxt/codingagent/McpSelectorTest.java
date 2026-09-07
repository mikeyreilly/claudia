package com.quaxt.codingagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McpSelectorTest {
	@Test
	void startsAtTheFirstItemWhenTheServerListIsShorterThanTheViewport() {
		assertEquals(0, CodingAgentCli.mcpSelectorVisibleStart(1, 10, 0));
	}

	@Test
	void keepsTheSelectionCenteredWithinAFullViewport() {
		assertEquals(5, CodingAgentCli.mcpSelectorVisibleStart(20, 10, 10));
		assertEquals(10, CodingAgentCli.mcpSelectorVisibleStart(20, 10, 19));
	}
}
