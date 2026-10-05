package com.quaxt.claudia.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;

/**
 * Tool carrier whose execution is supplied as a JDK function value, for callers
 * that need an ad-hoc tool without a dedicated carrier type.
 */
public final class FunctionTool implements AgentTool {
	public String name;
	public String description;
	public ObjectNode parameters;
	public Function<ToolInvocation, ToolResult> execute;

	public FunctionTool(
			String name, String description, ObjectNode parameters, Function<ToolInvocation, ToolResult> execute) {
		this.name = name;
		this.description = description;
		this.parameters = parameters;
		this.execute = execute;
	}
}
