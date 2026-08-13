package com.quaxt.codingagent.tui;

/** ANSI palette used by terminal components. */
public record Theme(String name, String heading, String strong, String code, String muted, String reset) {
	private static final String CLEAR_TO_END_OF_LINE = "\u001b[K";
	private static final String DARK_PROMPT_BACKGROUND = "\u001b[48;5;236m";
	private static final String LIGHT_PROMPT_BACKGROUND = "\u001b[48;5;254m";

	public static final Theme DARK = new Theme("dark", "\u001b[1m\u001b[36m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m");
	public static final Theme LIGHT = new Theme("light", "\u001b[1m\u001b[34m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m");
	public static final Theme PLAIN = new Theme("plain", "", "", "", "", "");

	/** Bold green is reserved for the Ready activity so idle is recognizable at a glance. */
	public String readyStatus() {
		return name.equalsIgnoreCase("plain") ? "" : "\u001b[1;92m";
	}

	/** Active model and shell work; deliberately never green. */
	public String activeStatus() {
		return switch (name.toLowerCase()) {
			case "dark" -> "\u001b[1;96m";
			case "light" -> "\u001b[1;34m";
			default -> "";
		};
	}

	/** Tool execution; deliberately never green. */
	public String toolStatus() {
		return name.equalsIgnoreCase("plain") ? "" : "\u001b[1;95m";
	}

	/** Retry, cancellation, and configuration attention; deliberately never green. */
	public String warningStatus() {
		return name.equalsIgnoreCase("plain") ? "" : "\u001b[1;93m";
	}

	/** Background used to visually separate the editable prompt from chat output. */
	public String promptBackground() {
		return switch (name.toLowerCase()) {
			case "dark" -> DARK_PROMPT_BACKGROUND;
			case "light" -> LIGHT_PROMPT_BACKGROUND;
			default -> "";
		};
	}

	/** Styles every line as a full-width prompt area. */
	public String promptArea(String value) {
		String background = promptBackground();
		if (background.isEmpty()) return value;
		StringBuilder styled = new StringBuilder(value.length() + 32);
		styled.append(background).append(CLEAR_TO_END_OF_LINE);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '\n') {
				styled.append(reset).append(character);
				if (index + 1 < value.length()) styled.append(background).append(CLEAR_TO_END_OF_LINE);
			} else {
				styled.append(character);
			}
		}
		if (value.isEmpty() || value.charAt(value.length() - 1) != '\n') styled.append(reset);
		return styled.toString();
	}

	public static Theme named(String name) {
		return switch (name.toLowerCase()) {
			case "dark" -> DARK;
			case "light" -> LIGHT;
			case "plain" -> PLAIN;
			default -> throw new IllegalArgumentException("Unknown theme: " + name + " (expected dark, light, or plain)");
		};
	}
}
