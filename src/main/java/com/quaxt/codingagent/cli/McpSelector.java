package com.quaxt.codingagent.cli;

import com.quaxt.codingagent.CodingAgentOperations;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * State of the full-screen MCP server list and its drill-down for toggling
 * individual tools. Rendering, filtering, and input handling live in
 * CodingAgentCli.
 */
public final class McpSelector {
	/** One persisted enable/disable decision; a null tool name means the server itself. */
	public static final class Change {
		public String serverName;
		public String toolName;
		public boolean enabled;

		public Change(String serverName, String toolName, boolean enabled) {
			this.serverName = serverName;
			this.toolName = toolName;
			this.enabled = enabled;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Change that
					&& Objects.equals(serverName, that.serverName)
					&& Objects.equals(toolName, that.toolName)
					&& enabled == that.enabled;
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverName, toolName, enabled);
		}

		@Override
		public String toString() {
			return "Change[serverName=" + serverName + ", toolName=" + toolName + ", enabled=" + enabled + "]";
		}
	}

	public enum View {
		SERVERS,
		TOOLS
	}

	public CodingAgentOperations manager;
	/** Receives every applied change; may report a failed save with UncheckedIOException. */
	public Consumer<Change> onChange;
	public List<String> names = List.of();
	public List<String> filtered = List.of();
	public List<CodingAgentOperations.McpToolStatus> filteredTools = List.of();
	public StringBuilder query = new StringBuilder();
	public int queryCursor;
	public int selectedIndex;
	public int visibleStart;
	public int visibleCount;
	public int optionStartRow;
	public View view = View.SERVERS;
	public String toolServer;
	public String changeError;
	public boolean complete;

	public McpSelector() {}
}
