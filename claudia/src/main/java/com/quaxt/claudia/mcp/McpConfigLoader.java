package com.quaxt.claudia.mcp;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Carrier for the MCP section of Claudia's settings file: the settings path
 * and the environment used for {@code {env:NAME}} and {@code {file:path}}
 * substitutions. Parsing lives in ClaudiaOperations.
 */
public final class McpConfigLoader {
	public static final Pattern ENVIRONMENT = Pattern.compile("\\{env:([^}]+)}");
	public static final Pattern FILE = Pattern.compile("\\{file:([^}]+)}");

	public Path settingsPath;
	public Map<String, String> environment;

	public McpConfigLoader(Path settingsPath, Map<String, String> environment) {
		this.settingsPath = settingsPath;
		this.environment = environment;
	}
}
