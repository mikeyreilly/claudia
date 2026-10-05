package com.quaxt.claudia.tui;

import java.util.Objects;

/** Typed selector option carrier with separate display and fuzzy-search text. */
public final class SelectItem<T> {
	public T value;
	public String label;
	public String description;
	public String searchText;

	public SelectItem(T value, String label, String description, String searchText) {
		this.value = value;
		this.label = label;
		this.description = description;
		this.searchText = searchText;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof SelectItem<?> that
				&& Objects.equals(value, that.value)
				&& Objects.equals(label, that.label)
				&& Objects.equals(description, that.description)
				&& Objects.equals(searchText, that.searchText);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value, label, description, searchText);
	}

	@Override
	public String toString() {
		return "SelectItem[value=" + value + ", label=" + label + ", description=" + description
				+ ", searchText=" + searchText + "]";
	}
}
