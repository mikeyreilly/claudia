package com.quaxt.codingagent.cli;

import java.util.ArrayList;
import java.util.List;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.tui.FuzzyMatcher;
import com.quaxt.codingagent.tui.TerminalText;
import com.quaxt.codingagent.tui.Theme;
import com.quaxt.codingagent.tui.TuiComponent;
import com.quaxt.codingagent.tui.TuiInput;

/** Full-screen MCP status list whose Enter action toggles without closing it. */
final class McpSelector implements TuiComponent<Void> {
	private final McpManager manager;
	private final Runnable onChange;
	private final List<String> names;
	private List<String> filtered;
	private final StringBuilder query = new StringBuilder();
	private int queryCursor;
	private int selectedIndex;
	private int visibleStart;
	private int visibleCount;
	private int optionStartRow;
	private boolean complete;

	McpSelector(McpManager manager, Runnable onChange) {
		this.manager = manager;
		this.onChange = onChange;
		this.names = manager.statuses().stream().map(McpManager.ServerStatus::name).toList();
		this.filtered = names;
	}

	@Override
	public List<String> render(int width, int height, Theme theme) {
		List<String> lines = new ArrayList<>();
		lines.add(theme.heading() + TerminalText.truncatePlain("MCP Servers", width) + theme.reset());
		lines.add("");
		String before = query.substring(0, queryCursor);
		String after = query.substring(queryCursor);
		lines.add(TerminalText.truncatePlain("Search: " + before + "|" + after, width));
		lines.add("");
		optionStartRow = lines.size();
		visibleCount = Math.max(1, Math.min(10, height - 9));
		visibleStart = Math.max(
				0, Math.min(selectedIndex - visibleCount / 2, Math.max(0, filtered.size() - visibleCount)));
		int end = Math.min(filtered.size(), visibleStart + visibleCount);
		if (filtered.isEmpty()) {
			lines.add(theme.muted() + "  No matching servers" + theme.reset());
		} else {
			for (int index = visibleStart; index < end; index++) {
				McpManager.ServerStatus status = manager.status(filtered.get(index));
				String row = (index == selectedIndex ? "> " : "  ") + statusLine(status);
				row = TerminalText.truncatePlain(row, width);
				lines.add(index == selectedIndex ? theme.heading() + row + theme.reset() : row);
			}
			if (visibleStart > 0 || end < filtered.size()) {
				lines.add(theme.muted() + "  " + (selectedIndex + 1) + "/" + filtered.size() + theme.reset());
			}
		}
		lines.add("");
		if (!filtered.isEmpty()) {
			McpManager.ServerStatus selected = manager.status(filtered.get(selectedIndex));
			String detail = selected.message() == null ? selected.target() : selected.message();
			lines.add(theme.muted() + TerminalText.truncatePlain("  " + detail, width) + theme.reset());
			if (selected.authorizationUrl() != null) {
				String label = TerminalText.truncatePlain("Open: " + selected.authorizationUrl(), Math.max(1, width - 2));
				String link = TerminalText.hyperlink(label, selected.authorizationUrl());
				lines.add(theme.muted() + "  " + link + theme.reset());
			}
		}
		lines.add(theme.muted()
				+ TerminalText.truncatePlain("Type to filter  Up/Down move  Enter toggle/auth/retry  Esc close", width)
				+ theme.reset());
		return lines;
	}

	private static String statusLine(McpManager.ServerStatus status) {
		return switch (status.state()) {
			case CONNECTING -> "⋯ " + status.name() + "  Connecting";
			case AUTHENTICATING -> "⋯ " + status.name() + "  Waiting for OAuth";
			case AUTH_REQUIRED -> "! " + status.name() + "  Authentication required";
			case CONNECTED -> "✓ " + status.name() + "  Enabled · " + status.toolCount() + " tool(s)";
			case DISABLED -> "○ " + status.name() + "  Disabled";
			case FAILED -> "✗ " + status.name() + "  Failed";
		};
	}

	@Override
	public void handle(TuiInput input) {
		switch (input) {
			case TuiInput.Key key -> handleKey(key);
			case TuiInput.Mouse mouse -> handleMouse(mouse);
			case TuiInput.Resize ignored -> {}
		}
	}

	private void handleKey(TuiInput.Key key) {
		switch (key.type()) {
			case UP -> move(-1);
			case DOWN -> move(1);
			case PAGE_UP -> move(-Math.max(1, visibleCount));
			case PAGE_DOWN -> move(Math.max(1, visibleCount));
			case ENTER -> toggle();
			case ESCAPE, CANCEL -> complete = true;
			case CHARACTER, PASTE -> insert(key.text());
			case BACKSPACE -> backspace();
			case DELETE -> delete();
			case LEFT -> queryCursor = Math.max(0, queryCursor - 1);
			case RIGHT -> queryCursor = Math.min(query.length(), queryCursor + 1);
			case HOME -> queryCursor = 0;
			case END -> queryCursor = query.length();
			case CLEAR -> {
				query.setLength(0);
				queryCursor = 0;
				filter();
			}
			default -> {}
		}
	}

	private void handleMouse(TuiInput.Mouse mouse) {
		switch (mouse.action()) {
			case SCROLL_UP -> move(-1);
			case SCROLL_DOWN -> move(1);
			case PRESS -> {
				int offset = mouse.y() - 1 - optionStartRow;
				int index = visibleStart + offset;
				if (mouse.button() == 0 && offset >= 0 && offset < visibleCount && index < filtered.size()) {
					selectedIndex = index;
				}
			}
			default -> {}
		}
	}

	private void toggle() {
		if (filtered.isEmpty()) return;
		manager.toggleAsync(filtered.get(selectedIndex));
		onChange.run();
	}

	private void move(int delta) {
		if (!filtered.isEmpty()) selectedIndex = Math.floorMod(selectedIndex + delta, filtered.size());
	}

	private void insert(String text) {
		if (text == null || text.isEmpty()) return;
		String normalized = text.replace('\r', ' ').replace('\n', ' ');
		query.insert(queryCursor, normalized);
		queryCursor += normalized.length();
		filter();
	}

	private void backspace() {
		if (queryCursor == 0) return;
		int start = query.offsetByCodePoints(queryCursor, -1);
		query.delete(start, queryCursor);
		queryCursor = start;
		filter();
	}

	private void delete() {
		if (queryCursor >= query.length()) return;
		int end = query.offsetByCodePoints(queryCursor, 1);
		query.delete(queryCursor, end);
		filter();
	}

	private void filter() {
		filtered = FuzzyMatcher.filter(names, query.toString(), name -> {
			McpManager.ServerStatus status = manager.status(name);
			return name + " " + status.state() + " " + status.target();
		});
		selectedIndex = 0;
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public Void result() {
		return null;
	}
}
