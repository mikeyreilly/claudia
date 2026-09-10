package com.quaxt.codingagent.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registers definitions once and binds their handlers to a runtime's resources. */
public final class ToolRegistry<C> {
    private final Map<String, ToolDefinition<C>> definitions;

    public ToolRegistry(List<ToolDefinition<C>> definitions) {
        var registered = new LinkedHashMap<String, ToolDefinition<C>>();
        for (var definition : definitions) {
            if (registered.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate tool: " + definition.name());
            }
        }
        this.definitions = java.util.Collections.unmodifiableMap(registered);
    }

    public List<AgentTool> bind(C context) {
        return definitions.values().stream().map(definition -> definition.bind(context)).toList();
    }

    public Optional<String> describeCall(String name, ObjectNode arguments) {
        var definition = definitions.get(name);
        return definition == null ? Optional.empty() : Optional.of(definition.describeCall().apply(arguments));
    }
}
