package com.quaxt.claudia.tui;

import java.util.List;

/**
 * Differential line-renderer state for terminal regions. The frame diffing
 * lives in ClaudiaCli; callers write the resulting ANSI sequence to the
 * terminal themselves.
 */
public final class AnsiRenderer {
	public List<String> previousLines = List.of();

	public AnsiRenderer() {}
}
