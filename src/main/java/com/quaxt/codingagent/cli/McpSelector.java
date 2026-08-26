package com.quaxt.codingagent.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.tui.FuzzyMatcher;
import com.quaxt.codingagent.tui.TerminalText;
import com.quaxt.codingagent.tui.Theme;
import com.quaxt.codingagent.tui.TuiComponent;
import com.quaxt.codingagent.tui.TuiInput;

/** Full-screen MCP server list with a drill-down for toggling individual tools. */
final class McpSelector implements TuiComponent<Void> {
	record Change(String serverName, String toolName, boolean enabled) {
		boolean isToolChange() {
			return toolName != null;
		}
	}

	@FunctionalInterface
	interface ChangeListener {
		void onChange(Change change) throws IOException;
	}

	private enum View {
		SERVERS,
		TOOLS
	}

	private final McpManager manager;
	private final ChangeListener onChange;
	private final List<String> names;
	private List<String> filtered;
	private List<McpManager.ToolStatus> filteredTools = List.of();
	private final StringBuilder query = new StringBuilder();
	private int queryCursor;
	private int selectedIndex;
	private int visibleStart;
	private int visibleCount;
	private int optionStartRow;
	private View view = View.SERVERS;
	private String toolServer;
	private String changeError;
	private boolean complete;

	McpSelector(McpManager manager, ChangeListener onChange) {
		this.manager = manager;
		this.onChange = onChange;
		this.names = manager.statuses().stream().map(McpManager.ServerStatus::name).toList();
		this.filtered = names;
	}

	@Override
	public List<String> render(int width, int height, Theme theme) {
		if (view == View.TOOLS) refreshTools();

		List<String> lines = new ArrayList<>();
		String title = view == View.SERVERS ? "MCP Servers" : "MCP Tools: " + toolServer;
		lines.add(theme.heading() + TerminalText.truncatePlain(title, width) + theme.reset());
		lines.add("");
		String before = query.substring(0, queryCursor);
		String after = query.substring(queryCursor);
		lines.add(TerminalText.truncatePlain("Search: " + before + "|" + after, width));
		lines.add("");
		optionStartRow = lines.size();
		visibleCount = Math.max(1, Math.min(10, height - 9));
		int itemCount = itemCount();
		visibleStart = Math.max(0, Math.min(selectedIndex - visibleCount / 2, Math.max(0, itemCount - visibleCount)));
		int end = Math.min(itemCount, visibleStart + visibleCount);
		if (itemCount == 0) {
			String empty = view == View.SERVERS ? "  No matching servers" : "  No tools available";
			lines.add(theme.muted() + empty + theme.reset());
		} else {
			for (int index = visibleStart; index < end; index++) {
				String row = (index == selectedIndex ? "> " : "  ") + statusLine(index);
				row = TerminalText.truncatePlain(row, width);
				lines.add(index == selectedIndex ? theme.heading() + row + theme.reset() : row);
			}
			if (visibleStart > 0 || end < itemCount) {
				lines.add(theme.muted() + "  " + (selectedIndex + 1) + "/" + itemCount + theme.reset());
			}
		}
		lines.add("");
		String detail = changeError == null ? detail() : changeError;
		if (detail != null) {
			String style = changeError == null ? theme.muted() : theme.warningStatus();
			lines.add(style + TerminalText.truncatePlain("  " + detail, width) + theme.reset());
		}
		if (view == View.SERVERS && !filtered.isEmpty()) {
			McpManager.ServerStatus selected = manager.status(filtered.get(selectedIndex));
			if (selected.authorizationUrl() != null) {
				String label = TerminalText.truncatePlain("Open: " + selected.authorizationUrl(), Math.max(1, width - 2));
				String link = TerminalText.hyperlink(label, selected.authorizationUrl());
				lines.add(theme.muted() + "  " + link + theme.reset());
			}
		}
		String hint = view == View.SERVERS
				? "Type to filter  Up/Down move  Enter toggle/auth/retry  Tab tools  Esc close"
				: "Type to filter  Up/Down move  Enter toggle  Tab/Esc servers";
		lines.add(theme.muted() + TerminalText.truncatePlain(hint, width) + theme.reset());
		return lines;
	}

	private int itemCount() {
		return view == View.SERVERS ? filtered.size() : filteredTools.size();
	}

	private String statusLine(int index) {
		return view == View.SERVERS ? serverStatusLine(manager.status(filtered.get(index))) : toolStatusLine(filteredTools.get(index));
	}

	private static String serverStatusLine(McpManager.ServerStatus status) {
		return switch (status.state()) {
			case CONNECTING -> "⋯ " + status.name() + "  Connecting";
			case AUTHENTICATING -> "⋯ " + status.name() + "  Waiting for OAuth";
			case AUTH_REQUIRED -> "! " + status.name() + "  Authentication required";
			case CONNECTED -> "✓ " + status.name() + "  Enabled · " + toolCount(status);
			case DISABLED -> "○ " + status.name() + "  Disabled";
			case FAILED -> "✗ " + status.name() + "  Failed";
		};
	}

	private static String toolCount(McpManager.ServerStatus status) {
		if (status.enabledToolCount() == status.toolCount()) return status.toolCount() + " tool(s)";
		return status.enabledToolCount() + "/" + status.toolCount() + " tool(s)";
	}

	private static String toolStatusLine(McpManager.ToolStatus status) {
		return (status.enabled() ? "✓ " : "○ ") + status.name() + "  " + (status.enabled() ? "Enabled" : "Disabled");
	}

	private String detail() {
		if (view == View.SERVERS) {
			if (filtered.isEmpty()) return null;
			McpManager.ServerStatus selected = manager.status(filtered.get(selectedIndex));
			return selected.message() == null ? selected.target() : selected.message();
		}
		if (filteredTools.isEmpty()) return null;
		String description = filteredTools.get(selectedIndex).description();
		return description.isBlank() ? "No description" : description;
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
			case TAB -> toggleView();
			case ESCAPE, CANCEL -> {
				if (view == View.TOOLS) closeTools();
				else complete = true;
			}
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
				if (mouse.button() == 0 && offset >= 0 && offset < visibleCount && index < itemCount()) {
					selectedIndex = index;
				}
			}
			default -> {}
		}
	}

	private void toggle() {
		if (view == View.SERVERS) {
			if (filtered.isEmpty()) return;
			McpManager.ServerStatus status = manager.toggleAsync(filtered.get(selectedIndex));
			notifyChange(new Change(status.name(), null, manager.isEnabled(status.name())));
		} else {
			toggleTool();
		}
	}

	private void toggleTool() {
		refreshTools();
		if (filteredTools.isEmpty()) return;
		try {
			McpManager.ToolStatus status = manager.toggleTool(toolServer, filteredTools.get(selectedIndex).name());
			notifyChange(new Change(status.serverName(), status.name(), status.enabled()));
			refreshTools();
		} catch (IllegalStateException | IllegalArgumentException ignored) {
			// The server or its catalog may have changed while this selector was open.
			refreshTools();
		}
	}

	private void notifyChange(Change change) {
		try {
			onChange.onChange(change);
			changeError = null;
		} catch (IOException error) {
			String message = error.getMessage();
			String detail = message == null || message.isBlank() ? error.toString() : message;
			changeError = "Change applied, but not saved: " + detail.replaceAll("\\s+", " ").trim();
		}
	}

	private void toggleView() {
		if (view == View.SERVERS) openTools();
		else closeTools();
	}

	private void openTools() {
		if (view == View.TOOLS || filtered.isEmpty()) return;
		String server = filtered.get(selectedIndex);
		if (manager.status(server).state() != McpManager.State.CONNECTED) return;
		view = View.TOOLS;
		toolServer = server;
		clearQuery();
		refreshTools();
	}

	private void closeTools() {
		if (view != View.TOOLS) return;
		String server = toolServer;
		view = View.SERVERS;
		toolServer = null;
		clearQuery();
		filter();
		int index = filtered.indexOf(server);
		if (index >= 0) selectedIndex = index;
	}

	private void move(int delta) {
		if (itemCount() > 0) selectedIndex = Math.floorMod(selectedIndex + delta, itemCount());
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

	private void clearQuery() {
		query.setLength(0);
		queryCursor = 0;
	}

	private void filter() {
		if (view == View.SERVERS) {
			filtered = FuzzyMatcher.filter(names, query.toString(), name -> {
				McpManager.ServerStatus status = manager.status(name);
				return name + " " + status.state() + " " + status.target();
			});
		} else {
			refreshTools();
		}
		selectedIndex = 0;
	}

	private void refreshTools() {
		if (toolServer == null) {
			filteredTools = List.of();
			return;
		}
		List<McpManager.ToolStatus> tools = manager.toolStatuses(toolServer);
		filteredTools = FuzzyMatcher.filter(
				tools,
				query.toString(),
				tool -> tool.name() + " " + tool.description() + " " + (tool.enabled() ? "enabled" : "disabled"));
		if (selectedIndex >= filteredTools.size()) selectedIndex = Math.max(0, filteredTools.size() - 1);
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
