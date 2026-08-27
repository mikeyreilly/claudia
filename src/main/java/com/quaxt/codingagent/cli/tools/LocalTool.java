package com.quaxt.codingagent.cli.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.function.Consumer;
import com.quaxt.codingagent.agent.AgentTool;

/**
 * Carrier for one built-in local tool: the model-visible metadata plus the
 * working directory, path observer, and optional git-ignore/shell settings its
 * execution needs. Execution lives in CodingAgentOperations.
 */
public final class LocalTool implements AgentTool {
	public BuiltInTools.Kind kind;
	public Path cwd;
	public String name;
	public String description;
	public ObjectNode parameters;
	public Consumer<Path> onPathAccess;
	public GitIgnore gitIgnore;
	public BuiltInTools.Shell shell;

	public LocalTool(
			BuiltInTools.Kind kind,
			Path cwd,
			String name,
			String description,
			ObjectNode parameters,
			Consumer<Path> onPathAccess,
			GitIgnore gitIgnore,
			BuiltInTools.Shell shell) {
		this.kind = kind;
		this.cwd = cwd;
		this.name = name;
		this.description = description;
		this.parameters = parameters;
		this.onPathAccess = onPathAccess;
		this.gitIgnore = gitIgnore;
		this.shell = shell;
	}
}
