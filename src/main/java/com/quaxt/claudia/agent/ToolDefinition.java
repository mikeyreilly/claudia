package com.quaxt.claudia.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.Function;

/** One tool's metadata, typed arguments, execution handler, and presentation. */
public record ToolDefinition<C>(String name, String description, ToolParameters parameters,
        Handler<C> handler, Function<ObjectNode, String> describeCall) {
    @FunctionalInterface
    public interface Handler<C> {
        AgentTool.ToolResult execute(C context, ToolParameters.Arguments arguments, ToolInvocation invocation) throws Exception;
    }

    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
        Objects.requireNonNull(description);
        Objects.requireNonNull(parameters);
        Objects.requireNonNull(handler);
        Objects.requireNonNull(describeCall);
    }

    public AgentTool bind(C context) {
        return new Bound<>(this, context);
    }

    /** A registered definition bound to the resources owned by one runtime. */
    public record Bound<C>(ToolDefinition<C> definition, C context) implements AgentTool {
        public AgentTool.ToolResult execute(ToolInvocation invocation) throws Exception {
            return definition.handler.execute(context, definition.parameters.parse(invocation.arguments), invocation);
        }
    }
}
