package com.quaxt.codingagent.tui;

import java.util.Objects;

/**
 * ANSI palette carrier used by terminal components. The derived accents, the
 * prompt background, and prompt-area styling are computed by the static
 * operations in CodingAgentOperations.
 */
public final class Theme {
	public static final String CLEAR_TO_END_OF_LINE = "\u001b[K";
	public static final String DARK_PROMPT_BACKGROUND = "\u001b[48;5;236m";
	public static final String LIGHT_PROMPT_BACKGROUND = "\u001b[48;5;254m";

	public static final Theme DARK =
			new Theme("dark", "\u001b[1m\u001b[36m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m");
	public static final Theme LIGHT =
			new Theme("light", "\u001b[1m\u001b[34m", "\u001b[1m", "\u001b[2m", "\u001b[2m", "\u001b[0m");
	public static final Theme PLAIN = new Theme("plain", "", "", "", "", "");

	public String name;
	public String heading;
	public String strong;
	public String code;
	public String muted;
	public String reset;

	public Theme(String name, String heading, String strong, String code, String muted, String reset) {
		this.name = name;
		this.heading = heading;
		this.strong = strong;
		this.code = code;
		this.muted = muted;
		this.reset = reset;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Theme that
				&& Objects.equals(name, that.name)
				&& Objects.equals(heading, that.heading)
				&& Objects.equals(strong, that.strong)
				&& Objects.equals(code, that.code)
				&& Objects.equals(muted, that.muted)
				&& Objects.equals(reset, that.reset);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, heading, strong, code, muted, reset);
	}

	@Override
	public String toString() {
		return "Theme[name=" + name + ", heading=" + heading + ", strong=" + strong
				+ ", code=" + code + ", muted=" + muted + ", reset=" + reset + "]";
	}
}
