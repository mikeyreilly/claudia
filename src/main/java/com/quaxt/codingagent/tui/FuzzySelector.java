package com.quaxt.codingagent.tui;

import java.util.ArrayList;
import java.util.List;

/** Searchable, scrollable selector component for models, settings, and similar menus. */
public final class FuzzySelector<T> implements TuiComponent<T> {
	private final String title;
	private final List<SelectItem<T>> items;
	private final SelectItem<T> currentItem;
	private final boolean searchable;
	private List<SelectItem<T>> filteredItems;
	private final StringBuilder query = new StringBuilder();
	private int queryCursor;
	private int selectedIndex;
	private int visibleStart;
	private int optionStartRow;
	private int visibleCount;
	private boolean complete;
	private T result;

	public FuzzySelector(String title, List<SelectItem<T>> items, int initialIndex, boolean searchable) {
		if (items.isEmpty()) {
			throw new IllegalArgumentException("items must not be empty");
		}
		this.title = title;
		this.items = List.copyOf(items);
		this.filteredItems = this.items;
		this.selectedIndex = initialIndex < 0 ? 0 : Math.min(initialIndex, items.size() - 1);
		this.currentItem = initialIndex < 0 ? null : this.items.get(this.selectedIndex);
		this.searchable = searchable;
	}

	@Override
	public List<String> render(int width, int height, Theme theme) {
		List<String> lines = new ArrayList<>();
		lines.add(theme.heading() + TerminalText.truncatePlain(title, width) + theme.reset());
		lines.add("");
		if (searchable) {
			String beforeCursor = query.substring(0, queryCursor);
			String afterCursor = query.substring(queryCursor);
			String search = "Search: " + beforeCursor + "|" + afterCursor;
			lines.add(TerminalText.truncatePlain(search, width));
			lines.add("");
		}

		optionStartRow = lines.size();
		int reservedLines = lines.size() + 3;
		visibleCount = Math.max(1, Math.min(10, height - reservedLines));
		visibleStart = Math.max(
				0,
				Math.min(
						selectedIndex - (visibleCount / 2),
						Math.max(0, filteredItems.size() - visibleCount)));
		int visibleEnd = Math.min(filteredItems.size(), visibleStart + visibleCount);

		if (filteredItems.isEmpty()) {
			lines.add(theme.muted() + "  No matching options" + theme.reset());
		} else {
			for (int index = visibleStart; index < visibleEnd; index++) {
				SelectItem<T> item = filteredItems.get(index);
				boolean selected = index == selectedIndex;
				boolean current = item == currentItem;
				String suffix = current ? " *" : "";
				String description = item.description().isBlank() ? "" : "  " + item.description();
				String row = (selected ? "> " : "  ") + item.label() + suffix + description;
				row = TerminalText.truncatePlain(row, width);
				lines.add(selected ? theme.heading() + row + theme.reset() : row);
			}
			if (visibleStart > 0 || visibleEnd < filteredItems.size()) {
				lines.add(theme.muted()
						+ "  "
						+ (selectedIndex + 1)
						+ "/"
						+ filteredItems.size()
						+ theme.reset());
			}
		}
		lines.add("");
		String currentHint = currentItem == null ? "" : "  * current";
		String hint = (searchable
						? "Type to filter  Up/Down move  Enter select  Esc cancel"
						: "Up/Down move  Enter select  Esc cancel")
				+ currentHint;
		lines.add(theme.muted() + TerminalText.truncatePlain(hint, width) + theme.reset());
		return lines;
	}

	@Override
	public void handle(TuiInput input) {
		switch (input) {
			case TuiInput.Key key -> handleKey(key);
			case TuiInput.Mouse mouse -> handleMouse(mouse);
			case TuiInput.Resize ignored -> {
				// Rendering derives its viewport directly from the latest dimensions.
			}
		}
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public T result() {
		return result;
	}

	String query() {
		return query.toString();
	}

	List<SelectItem<T>> filteredItems() {
		return filteredItems;
	}

	int selectedIndex() {
		return selectedIndex;
	}

	private void handleKey(TuiInput.Key key) {
		switch (key.type()) {
			case UP -> move(-1);
			case DOWN -> move(1);
			case PAGE_UP -> move(-Math.max(1, visibleCount));
			case PAGE_DOWN -> move(Math.max(1, visibleCount));
			case ENTER -> select();
			case ESCAPE, CANCEL -> complete = true;
			case CHARACTER, PASTE -> insert(key.text());
			case BACKSPACE -> backspace();
			case DELETE -> delete();
			case LEFT -> queryCursor = Math.max(0, queryCursor - 1);
			case RIGHT -> queryCursor = Math.min(query.length(), queryCursor + 1);
			case HOME -> {
				if (searchable) {
					queryCursor = 0;
				} else if (!filteredItems.isEmpty()) {
					selectedIndex = 0;
				}
			}
			case END -> {
				if (searchable) {
					queryCursor = query.length();
				} else if (!filteredItems.isEmpty()) {
					selectedIndex = filteredItems.size() - 1;
				}
			}
			case CLEAR -> {
				query.setLength(0);
				queryCursor = 0;
				filter();
			}
			default -> {
				// Other normalized keys do not affect selector state.
			}
		}
	}

	private void handleMouse(TuiInput.Mouse mouse) {
		switch (mouse.action()) {
			case SCROLL_UP -> move(-1);
			case SCROLL_DOWN -> move(1);
			case PRESS -> {
				int row = mouse.y() - 1;
				int itemOffset = row - optionStartRow;
				if (mouse.button() == 0 && itemOffset >= 0 && itemOffset < visibleCount) {
					int index = visibleStart + itemOffset;
					if (index < filteredItems.size()) {
						selectedIndex = index;
					}
				}
			}
			default -> {
				// Release and drag events are currently informational.
			}
		}
	}

	private void move(int delta) {
		if (filteredItems.isEmpty()) {
			return;
		}
		selectedIndex = Math.floorMod(selectedIndex + delta, filteredItems.size());
	}

	private void select() {
		if (filteredItems.isEmpty()) {
			return;
		}
		result = filteredItems.get(selectedIndex).value();
		complete = true;
	}

	private void insert(String text) {
		if (!searchable || text == null || text.isEmpty()) {
			return;
		}
		String normalized = text.replace("\r", " ").replace("\n", " ");
		query.insert(queryCursor, normalized);
		queryCursor += normalized.length();
		filter();
	}

	private void backspace() {
		if (!searchable || queryCursor == 0) {
			return;
		}
		int start = query.offsetByCodePoints(queryCursor, -1);
		query.delete(start, queryCursor);
		queryCursor = start;
		filter();
	}

	private void delete() {
		if (!searchable || queryCursor >= query.length()) {
			return;
		}
		int end = query.offsetByCodePoints(queryCursor, 1);
		query.delete(queryCursor, end);
		filter();
	}

	private void filter() {
		filteredItems = FuzzyMatcher.filter(items, query.toString(), SelectItem::searchText);
		if (query.isEmpty()) {
			int currentIndex = filteredItems.indexOf(currentItem);
			selectedIndex = currentIndex < 0 ? 0 : currentIndex;
		} else {
			selectedIndex = 0;
		}
	}
}
