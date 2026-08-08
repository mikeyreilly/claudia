package com.quaxt.codingagent.tui;

import java.util.Objects;

/** Typed selector option with separate display and fuzzy-search text. */
public record SelectItem<T>(T value, String label, String description, String searchText) {
	public SelectItem {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(label, "label");
		description = description == null ? "" : description;
		searchText = searchText == null || searchText.isBlank()
				? label + (description.isBlank() ? "" : " " + description)
				: searchText;
	}

	public SelectItem(T value, String label, String description) {
		this(value, label, description, null);
	}

	public SelectItem(T value, String label) {
		this(value, label, "", null);
	}
}
