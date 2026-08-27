package com.quaxt.codingagent.tui;

import java.util.List;

/**
 * State of the slash-command panel shown below the prompt. Matching,
 * navigation, and rendering live in CodingAgentOperations.
 */
public final class CommandSuggestions {
	public static final int VISIBLE_COMMANDS = 4;

	/** Alphabetized, de-duplicated slash commands offered by the panel. */
	public List<String> commands;
	public String query;
	public String dismissedBuffer;
	public List<String> matches = List.of();
	public int selectedIndex;
	public int visibleStart;

	public CommandSuggestions(List<String> commands) {
		this.commands = commands;
	}
}
