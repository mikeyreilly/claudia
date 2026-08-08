package com.quaxt.codingagent.tui;

/** ANSI palette used by terminal components. */
public record Theme(String name, String heading, String strong, String code, String muted, String reset) {
	public static final Theme DARK = new Theme("dark", "\u001b[1m\u001b[36m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m");
	public static final Theme LIGHT = new Theme("light", "\u001b[1m\u001b[34m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m");
	public static final Theme PLAIN = new Theme("plain", "", "", "", "", "");

	public static Theme named(String name) {
		return switch (name.toLowerCase()) {
			case "dark" -> DARK;
			case "light" -> LIGHT;
			case "plain" -> PLAIN;
			default -> throw new IllegalArgumentException("Unknown theme: " + name + " (expected dark, light, or plain)");
		};
	}
}
