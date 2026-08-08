package com.quaxt.codingagent.ai.types;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool definition sent to the model. Parameters are a JSON Schema object
 * (the TS version uses TypeBox; here plain Jackson JSON).
 */
public record Tool(String name, String description, ObjectNode parameters) {
	public Tool {
		if (name == null || description == null || parameters == null) {
			throw new IllegalArgumentException("name, description, and parameters must not be null");
		}
	}
}
