package com.quaxt.codingagent.tui;

import java.util.Map;
import org.jline.keymap.KeyMap;

/** Default editor and application bindings, exposed for future user settings. */
public final class Keybindings {
	public static final Map<String, String> DEFAULT_EDITOR_KEYBINDINGS = Map.of(
			"submit", "enter",
			"cancel", "ctrl-c",
			"deletePreviousWord", "alt-backspace");
	public static final Map<String, String> DEFAULT_APP_KEYBINDINGS = Map.of(
			"exit", "ctrl-d",
			"interrupt", "ctrl-c",
			"suspend", "ctrl-z",
			"expandTools", "ctrl-o",
			"toggleThinking", "ctrl-t");

	private Keybindings() {}

	static String appSequence(String action) {
		String binding = DEFAULT_APP_KEYBINDINGS.get(action);
		if (binding != null && binding.startsWith("ctrl-") && binding.length() == 6) {
			return KeyMap.ctrl(binding.charAt(5));
		}
		throw new IllegalArgumentException("Unsupported application keybinding: " + action + "=" + binding);
	}

	static TuiInput.KeyType appKeyType(int value) {
		if (matches(value, "exit")) return TuiInput.KeyType.EXIT;
		if (matches(value, "interrupt")) return TuiInput.KeyType.CANCEL;
		if (matches(value, "suspend")) return TuiInput.KeyType.SUSPEND;
		if (matches(value, "expandTools")) return TuiInput.KeyType.EXPAND_TOOLS;
		if (matches(value, "toggleThinking")) return TuiInput.KeyType.TOGGLE_THINKING;
		return null;
	}

	private static boolean matches(int value, String action) {
		String sequence = appSequence(action);
		return sequence.length() == 1 && sequence.charAt(0) == value;
	}
}
