package com.quaxt.codingagent.tui;

import java.util.List;

/**
 * Differential line-renderer state for terminal regions. The frame diffing
 * lives in CodingAgentOperations; callers write the returned ANSI sequence
 * themselves, allowing it to be used with JLine or another terminal
 * abstraction.
 */
public final class AnsiRenderer {
	public List<String> previousLines = List.of();

	public AnsiRenderer() {}
}
