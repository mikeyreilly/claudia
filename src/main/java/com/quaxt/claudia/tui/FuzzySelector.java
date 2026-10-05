package com.quaxt.claudia.tui;

import java.util.List;

/**
 * State of the searchable, scrollable selector used for models, settings, and
 * similar menus. Filtering, input handling, and rendering live in
 * ClaudiaCli.
 */
public final class FuzzySelector<T> {
	public String title;
	public List<SelectItem<T>> items;
	public SelectItem<T> currentItem;
	public boolean searchable;
	public List<SelectItem<T>> filteredItems;
	public StringBuilder query = new StringBuilder();
	public int queryCursor;
	public int selectedIndex;
	public int visibleStart;
	public int optionStartRow;
	public int visibleCount;
	public boolean complete;
	public T result;

	public FuzzySelector(
			String title,
			List<SelectItem<T>> items,
			List<SelectItem<T>> filteredItems,
			SelectItem<T> currentItem,
			int selectedIndex,
			boolean searchable) {
		this.title = title;
		this.items = items;
		this.filteredItems = filteredItems;
		this.currentItem = currentItem;
		this.selectedIndex = selectedIndex;
		this.searchable = searchable;
	}
}
