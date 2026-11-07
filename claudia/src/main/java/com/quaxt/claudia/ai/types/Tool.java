package com.quaxt.claudia.ai.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Tool definition sent to the model. Parameters are a JSON Schema object
 * (the TS version uses TypeBox; here plain Jackson JSON).
 */
public final class Tool {
	public String name;
	public String description;
	public ObjectNode parameters;

	public Tool(String name, String description, ObjectNode parameters) {
		this.name = name;
		this.description = description;
		this.parameters = parameters;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Tool that
				&& Objects.equals(name, that.name)
				&& Objects.equals(description, that.description)
				&& Objects.equals(parameters, that.parameters);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, description, parameters);
	}

	@Override
	public String toString() {
		return "Tool[name=" + name + ", description=" + description + ", parameters=" + parameters + "]";
	}
}
