package com.quaxt.codingagent.mcp;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Removes noisy keys from JSON results returned by matching MCP tools. */
public record McpResultFilter(String tool, List<String> dropKeys) {
	public McpResultFilter {
		Objects.requireNonNull(tool, "tool");
		dropKeys = List.copyOf(dropKeys);
	}

	boolean matches(String toolName) {
		if (tool.indexOf('*') < 0 && tool.indexOf('?') < 0) return tool.equals(toolName);
		StringBuilder regex = new StringBuilder("^");
		for (int index = 0; index < tool.length(); index++) {
			switch (tool.charAt(index)) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append('.');
				default -> regex.append(Pattern.quote(String.valueOf(tool.charAt(index))));
			}
		}
		return Pattern.compile(regex.append('$').toString(), Pattern.DOTALL).matcher(toolName).matches();
	}
}
