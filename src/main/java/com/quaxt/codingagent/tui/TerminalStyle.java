package com.quaxt.codingagent.tui;

/** Fixed ANSI styling for the terminal interface. */
public final class TerminalStyle {
	public static final String HEADING = "\u001b[1m\u001b[36m";
	public static final String STRONG = "\u001b[1m";
	public static final String MUTED = "\u001b[2m";
	public static final String RESET = "\u001b[0m";
	public static final String READY = "\u001b[1;92m";
	public static final String ACTIVE = "\u001b[1;96m";
	public static final String TOOL = "\u001b[1;95m";
	public static final String WARNING = "\u001b[1;93m";
	public static final String CLEAR_TO_END_OF_LINE = "\u001b[K";
	public static final String PROMPT_BACKGROUND = "\u001b[48;5;236m";

	private TerminalStyle() {}
}
