package com.quaxt.codingagent.mcp;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Carrier for the configured MCP sessions of one process: the workspace root,
 * the shared OAuth client, and the per-server runtime state. Connecting,
 * filtering, toggling, and shutdown live in CodingAgentOperations; each
 * runtime's {@code lock} guards its own mutable state.
 */
public final class McpManager {
	public enum State {
		CONNECTING,
		AUTHENTICATING,
		AUTH_REQUIRED,
		CONNECTED,
		DISABLED,
		FAILED
	}

	/** Snapshot of one configured server. */
	public static final class ServerStatus {
		public String name;
		public State state;
		public String message;
		public int toolCount;
		public int enabledToolCount;
		public String target;
		public String authorizationUrl;

		public ServerStatus(
				String name,
				State state,
				String message,
				int toolCount,
				int enabledToolCount,
				String target,
				String authorizationUrl) {
			this.name = name;
			this.state = state;
			this.message = message;
			this.toolCount = toolCount;
			this.enabledToolCount = enabledToolCount;
			this.target = target;
			this.authorizationUrl = authorizationUrl;
		}
	}

	/** One cached MCP tool and whether it is exposed to the model in this process. */
	public static final class ToolStatus {
		public String serverName;
		public String name;
		public String description;
		public boolean enabled;

		public ToolStatus(String serverName, String name, String description, boolean enabled) {
			this.serverName = serverName;
			this.name = name;
			this.description = description;
			this.enabled = enabled;
		}
	}

	/** Mutable state of one configured server; {@code lock} guards every field below it. */
	public static final class Runtime {
		public Object lock = new Object();
		public String name;
		public McpServerConfig config;
		public long generation;
		public boolean enabled;
		public State state;
		public String message;
		public McpClient client;
		public List<McpClient.ToolDefinition> tools = List.of();
		public Set<String> disabledTools = new HashSet<>();
		public Thread connector;
		public String authorizationUrl;

		public Runtime(String name, McpServerConfig config) {
			this.name = name;
			this.config = config;
		}
	}

	public Path workspace;
	public McpOAuthClient oauth;
	public LinkedHashMap<String, Runtime> servers = new LinkedHashMap<>();
	public volatile boolean closed;

	public McpManager() {}
}
