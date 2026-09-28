package com.quaxt.codingagent.tui;

import java.util.List;

/**
 * Differential line-renderer state for terminal regions. The frame diffing
 * lives in CodingAgentCli; callers write the resulting ANSI sequence to the
 * terminal themselves.
 */
public final class AnsiRenderer {
	public List<String> previousLines = List.of();

	public AnsiRenderer() {}
}
