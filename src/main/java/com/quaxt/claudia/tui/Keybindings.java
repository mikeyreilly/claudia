package com.quaxt.claudia.tui;

import java.util.Map;

/**
 * Default editor and application binding data, exposed for future user
 * settings. ClaudiaCli matches decoded keys against the application
 * bindings; the editor bindings describe the defaults built into
 * {@code com.quaxt.claudia.terminal.LineEditor}.
 */
public final class Keybindings {
	public static final Map<String, String> DEFAULT_EDITOR_KEYBINDINGS = Map.of(
			"submit", "enter",
			"newline", "shift-enter",
			"cancel", "ctrl-c",
			"deletePreviousWord", "alt-backspace");
	public static final Map<String, String> DEFAULT_APP_KEYBINDINGS = Map.of(
			"toggleAgentMode", "shift-tab",
			"exit", "ctrl-d",
			"interrupt", "escape",
			"suspend", "ctrl-z",
			"expandTools", "ctrl-o",
			"toggleThinking", "ctrl-t");

	public Keybindings() {}
}
