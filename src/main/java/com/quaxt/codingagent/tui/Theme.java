package com.quaxt.codingagent.tui;

import java.util.Objects;

/**
 * ANSI palette carrier used by terminal components. The derived accents, the
 * prompt background, and prompt-area styling are computed by the static
 * operations in CodingAgentOperations.
 */
public enum Theme {
	DARK("dark", "\u001b[1m\u001b[36m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m"),
	LIGHT("light", "\u001b[1m\u001b[34m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m"),
	PLAIN("plain", "", "", "", "", "");

	public static final String CLEAR_TO_END_OF_LINE = "\u001b[K";
	public static final String DARK_PROMPT_BACKGROUND = "\u001b[48;5;236m";
	public static final String LIGHT_PROMPT_BACKGROUND = "\u001b[48;5;254m";


	public String name1;
	public String heading;
	public String strong;
	public String code;
	public String muted;
	public String reset;

	Theme(String name, String heading, String strong, String code, String muted, String reset) {
		this.name1 = name;
		this.heading = heading;
		this.strong = strong;
		this.code = code;
		this.muted = muted;
		this.reset = reset;
	}

}
