package com.quaxt.codingagent.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** State and rendering for the slash-command panel shown below the prompt. */
final class CommandSuggestions {
	static final int VISIBLE_COMMANDS = 4;

	private final List<String> commands;
	private String query;
	private String dismissedBuffer;
	private List<String> matches = List.of();
	private int selectedIndex;
	private int visibleStart;

	CommandSuggestions(List<String> commands) {
		Objects.requireNonNull(commands, "commands");
		this.commands = commands.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(command -> command.startsWith("/") && command.length() > 1)
				.distinct()
				.sorted(Comparator.naturalOrder())
				.toList();
	}

	/** Moves the selection when a panel is open; returns false to let JLine handle the key. */
	boolean move(String buffer, int delta) {
		synchronize(buffer);
		if (matches.isEmpty()) return false;
		selectedIndex = Math.floorMod(selectedIndex + delta, matches.size());
		adjustViewport();
		return true;
	}

	/** Returns the selected command and dismisses the panel until the buffer is edited. */
	String accept(String buffer) {
		synchronize(buffer);
		if (matches.isEmpty()) return null;
		String selected = matches.get(selectedIndex);
		dismissedBuffer = selected;
		return selected;
	}

	List<String> render(String buffer, int width, Theme theme) {
		synchronize(buffer);
		if (matches.isEmpty()) return List.of();

		int visibleEnd = Math.min(matches.size(), visibleStart + VISIBLE_COMMANDS);
		List<String> visible = matches.subList(visibleStart, visibleEnd);
		int longestCommand = visible.stream()
				.mapToInt(TerminalText::visibleWidth)
				.max()
				.orElse(1);
		int innerWidth = Math.max(1, Math.min(longestCommand + 2, Math.max(1, width - 2)));
		String border = "─".repeat(innerWidth);
		List<String> lines = new ArrayList<>(visible.size() + 2);
		lines.add(styleMuted("╭" + border + "╮", theme));
		for (int index = visibleStart; index < visibleEnd; index++) {
			boolean selected = index == selectedIndex;
			String content = (selected ? "› " : "  ") + matches.get(index);
			content = TerminalText.truncatePlain(content, innerWidth);
			content += " ".repeat(Math.max(0, innerWidth - TerminalText.visibleWidth(content)));
			String styledContent = selected && !theme.heading().isEmpty()
					? theme.heading() + content + theme.reset()
					: content;
			lines.add(styleMuted("│", theme) + styledContent + styleMuted("│", theme));
		}
		lines.add(styleMuted("╰" + border + "╯", theme));
		return lines;
	}

	List<String> visibleCommands(String buffer) {
		synchronize(buffer);
		if (matches.isEmpty()) return List.of();
		return List.copyOf(matches.subList(
				visibleStart,
				Math.min(matches.size(), visibleStart + VISIBLE_COMMANDS)));
	}

	String selectedCommand(String buffer) {
		synchronize(buffer);
		return matches.isEmpty() ? null : matches.get(selectedIndex);
	}

	private void synchronize(String buffer) {
		String value = buffer == null ? "" : buffer;
		if (dismissedBuffer != null) {
			if (dismissedBuffer.equals(value)) {
				matches = List.of();
				query = value;
				selectedIndex = 0;
				visibleStart = 0;
				return;
			}
			dismissedBuffer = null;
		}
		if (value.equals(query)) return;
		query = value;
		selectedIndex = 0;
		visibleStart = 0;
		if (!isCommandPrefix(value)) {
			matches = List.of();
			return;
		}
		matches = commands.stream().filter(command -> command.startsWith(value)).toList();
	}

	private static boolean isCommandPrefix(String value) {
		if (value.isEmpty() || value.charAt(0) != '/') return false;
		for (int index = 1; index < value.length(); index++) {
			if (Character.isWhitespace(value.charAt(index))) return false;
		}
		return true;
	}

	private void adjustViewport() {
		if (selectedIndex < visibleStart) {
			visibleStart = selectedIndex;
		} else if (selectedIndex >= visibleStart + VISIBLE_COMMANDS) {
			visibleStart = selectedIndex - VISIBLE_COMMANDS + 1;
		}
		visibleStart = Math.min(visibleStart, Math.max(0, matches.size() - VISIBLE_COMMANDS));
	}

	private static String styleMuted(String value, Theme theme) {
		return theme.muted().isEmpty() ? value : theme.muted() + value + theme.reset();
	}
}
