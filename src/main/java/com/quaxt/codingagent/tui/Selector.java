package com.quaxt.codingagent.tui;

import java.io.IOException;
import java.util.List;

/** Typed facade for searchable pi TUI selectors. */
public final class Selector {
	private Selector() {}

	public static <T> T select(
			InteractiveTerminal terminal,
			String title,
			List<SelectItem<T>> options,
			int initialIndex,
			boolean searchable)
			throws IOException {
		if (options.isEmpty()) {
			throw new IllegalArgumentException("options must not be empty");
		}
		return terminal.run(new FuzzySelector<>(title, options, initialIndex, searchable));
	}

	public static String select(InteractiveTerminal terminal, String title, List<String> options) throws IOException {
		List<SelectItem<String>> items = options.stream().map(option -> new SelectItem<>(option, option)).toList();
		return select(terminal, title, items, -1, options.size() > 10);
	}
}
