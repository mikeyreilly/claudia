package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import com.quaxt.codingagent.agent.AgentTool;

/** Owns configured MCP sessions and supports runtime server and tool toggles. */
public final class McpManager implements AutoCloseable {
	public enum State {
		CONNECTING,
		AUTHENTICATING,
		AUTH_REQUIRED,
		CONNECTED,
		DISABLED,
		FAILED
	}

	public record ServerStatus(
			String name,
			State state,
			String message,
			int toolCount,
			int enabledToolCount,
			String target,
			String authorizationUrl) {}

	/** One cached MCP tool and whether it is exposed to the model in this process. */
	public record ToolStatus(String serverName, String name, String description, boolean enabled) {}

	private final Path workspace;
	private final McpOAuthClient oauth;
	private final LinkedHashMap<String, Runtime> servers = new LinkedHashMap<>();
	private volatile boolean closed;

	public McpManager(McpConfiguration configuration, Path workspace) {
		this(configuration, workspace, McpOAuthClient.defaultClient());
	}

	McpManager(McpConfiguration configuration, Path workspace, McpOAuthClient oauth) {
		this.workspace = workspace.toAbsolutePath().normalize();
		this.oauth = oauth;
		configuration.servers().forEach((name, config) -> servers.put(name, new Runtime(name, config)));
		for (Runtime runtime : servers.values()) {
			// Startup may refresh an existing token, but never opens a browser unexpectedly.
			if (runtime.config.enabled()) startConnect(runtime, false);
		}
	}

	public static McpManager loadDefault(Path workspace) throws IOException {
		return new McpManager(McpConfigLoader.loadDefault(), workspace);
	}

	public boolean isEmpty() {
		return servers.isEmpty();
	}

	public List<ServerStatus> statuses() {
		return servers.values().stream()
				.sorted(Comparator.comparing(runtime -> runtime.name))
				.map(this::snapshot)
				.toList();
	}

	public ServerStatus status(String name) {
		return snapshot(require(name));
	}

	/**
	 * Returns the current tool catalog for a connected server, including tools
	 * disabled for this process. A disconnected server has no current catalog.
	 */
	public List<ToolStatus> toolStatuses(String serverName) {
		Runtime runtime = require(serverName);
		synchronized (runtime.lock) {
			if (runtime.state != State.CONNECTED || runtime.client == null) return List.of();
			return runtime.tools.stream()
					.map(tool -> new ToolStatus(
							runtime.name, tool.name(), tool.description(), !runtime.disabledTools.contains(tool.name())))
					.toList();
		}
	}

	/**
	 * Enables a currently disabled tool or disables an enabled one without
	 * disconnecting its server. The choice lasts for this manager's lifetime,
	 * including a later reconnect of that server.
	 */
	public ToolStatus toggleTool(String serverName, String toolName) {
		Runtime runtime = require(serverName);
		synchronized (runtime.lock) {
			if (runtime.state != State.CONNECTED || runtime.client == null) {
				throw new IllegalStateException("MCP server is not connected: " + serverName);
			}
			McpClient.ToolDefinition definition = runtime.tools.stream()
					.filter(tool -> tool.name().equals(toolName))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
							"MCP tool is not available from " + serverName + ": " + toolName));
			boolean enabled;
			if (runtime.disabledTools.remove(toolName)) {
				enabled = true;
			} else {
				runtime.disabledTools.add(toolName);
				enabled = false;
			}
			return new ToolStatus(runtime.name, definition.name(), definition.description(), enabled);
		}
	}

	/** Waits for all currently-starting configured servers. */
	public void awaitReady() throws InterruptedException {
		while (true) {
			List<Thread> connecting = new ArrayList<>();
			for (Runtime runtime : servers.values()) {
				synchronized (runtime.lock) {
					if (runtime.connector != null) connecting.add(runtime.connector);
				}
			}
			if (connecting.isEmpty()) return;
			for (Thread thread : connecting) thread.join();
		}
	}

	/** Connects or retries a configured server and waits for that attempt. */
	public ServerStatus connect(String name) throws InterruptedException {
		Runtime runtime = require(name);
		Thread thread = startConnect(runtime, true);
		if (thread != null) thread.join();
		return snapshot(runtime);
	}

	/** Starts the same connect/authenticate action without blocking a TUI event loop. */
	public ServerStatus connectAsync(String name) {
		Runtime runtime = require(name);
		startConnect(runtime, true);
		return snapshot(runtime);
	}

	/** Disconnects a server for this process without changing its config file. */
	public ServerStatus disconnect(String name) {
		Runtime runtime = require(name);
		McpClient client;
		Thread connector;
		synchronized (runtime.lock) {
			runtime.generation++;
			connector = runtime.connector;
			runtime.connector = null;
			client = runtime.client;
			runtime.client = null;
			runtime.tools = List.of();
			runtime.state = State.DISABLED;
			runtime.message = null;
			runtime.authorizationUrl = null;
		}
		if (connector != null) connector.interrupt();
		if (client != null) client.close();
		return snapshot(runtime);
	}

	/** Connected/connecting means toggle off; disabled/failed means connect or retry. */
	public ServerStatus toggle(String name) throws InterruptedException {
		Runtime runtime = require(name);
		State state;
		synchronized (runtime.lock) {
			state = runtime.state;
		}
		return state == State.CONNECTED || state == State.CONNECTING || state == State.AUTHENTICATING
				? disconnect(name)
				: connect(name);
	}

	/** Asynchronous variant used by the full-screen selector. */
	public ServerStatus toggleAsync(String name) {
		Runtime runtime = require(name);
		State state;
		synchronized (runtime.lock) {
			state = runtime.state;
		}
		return state == State.CONNECTED || state == State.CONNECTING || state == State.AUTHENTICATING
				? disconnect(name)
				: connectAsync(name);
	}

	/** Returns the current model-visible tool adapters, with OpenCode-compatible names. */
	public List<AgentTool> tools() {
		LinkedHashMap<String, AgentTool> result = new LinkedHashMap<>();
		for (Runtime runtime : servers.values()) {
			McpClient client;
			List<McpClient.ToolDefinition> definitions;
			Set<String> disabledTools;
			synchronized (runtime.lock) {
				if (runtime.state != State.CONNECTED || runtime.client == null) continue;
				client = runtime.client;
				definitions = runtime.tools;
				disabledTools = Set.copyOf(runtime.disabledTools);
			}
			for (McpClient.ToolDefinition definition : definitions) {
				if (disabledTools.contains(definition.name())) continue;
				McpAgentTool tool = new McpAgentTool(
						runtime.name, definition, client, runtime.config.resultFilters());
				result.put(tool.name(), tool);
			}
		}
		return List.copyOf(result.values());
	}

	private Thread startConnect(Runtime runtime, boolean interactiveOAuth) {
		McpClient previous;
		Thread previousConnector;
		long generation;
		Thread connector;
		synchronized (runtime.lock) {
			if (closed) return null;
			if (runtime.state == State.CONNECTED && runtime.client != null) return null;
			previous = runtime.client;
			previousConnector = runtime.connector;
			runtime.client = null;
			runtime.tools = List.of();
			runtime.state = State.CONNECTING;
			runtime.message = null;
			runtime.authorizationUrl = null;
			generation = ++runtime.generation;
			connector = Thread.ofVirtual()
					.name("mcp-connect-" + McpAgentTool.sanitize(runtime.name))
					.unstarted(() -> connectAttempt(runtime, generation, interactiveOAuth));
			runtime.connector = connector;
		}
		if (previousConnector != null && previousConnector != connector) previousConnector.interrupt();
		if (previous != null) previous.close();
		connector.start();
		return connector;
	}

	private void connectAttempt(Runtime runtime, long generation, boolean interactiveOAuth) {
		McpClient candidate = null;
		try {
			candidate = McpClient.connect(
					runtime.name,
					runtime.config,
					workspace,
					oauth,
					interactiveOAuth,
					url -> authorizationStarted(runtime, generation, url.toString()));
			List<McpClient.ToolDefinition> tools = candidate.listTools();
			McpClient connected = candidate;
			candidate.onNotification((method, params) -> {
				if (method.equals("notifications/tools/list_changed")) refreshTools(runtime, generation, connected);
			});
			synchronized (runtime.lock) {
				if (closed || runtime.generation != generation || Thread.currentThread().isInterrupted()) return;
				runtime.client = candidate;
				runtime.tools = List.copyOf(tools);
				runtime.state = State.CONNECTED;
				runtime.message = null;
				runtime.authorizationUrl = null;
				candidate = null;
			}
		} catch (McpOAuthRequiredException error) {
			synchronized (runtime.lock) {
				if (!closed && runtime.generation == generation) {
					runtime.state = State.AUTH_REQUIRED;
					runtime.message = message(error);
					runtime.authorizationUrl = null;
					runtime.client = null;
					runtime.tools = List.of();
				}
			}
		} catch (Exception error) {
			synchronized (runtime.lock) {
				if (!closed && runtime.generation == generation) {
					runtime.state = State.FAILED;
					runtime.message = message(error);
					runtime.authorizationUrl = null;
					runtime.client = null;
					runtime.tools = List.of();
				}
			}
		} finally {
			if (candidate != null) candidate.close();
			synchronized (runtime.lock) {
				if (runtime.generation == generation && runtime.connector == Thread.currentThread()) {
					runtime.connector = null;
				}
			}
		}
	}

	private void authorizationStarted(Runtime runtime, long generation, String url) {
		synchronized (runtime.lock) {
			if (closed || runtime.generation != generation) return;
			runtime.state = State.AUTHENTICATING;
			runtime.message = "Complete OAuth authorization in your browser";
			runtime.authorizationUrl = url;
		}
	}

	private void refreshTools(Runtime runtime, long generation, McpClient client) {
		Thread.ofVirtual().name("mcp-tools-refresh").start(() -> {
			try {
				List<McpClient.ToolDefinition> tools = client.listTools();
				synchronized (runtime.lock) {
					if (runtime.generation == generation && runtime.client == client && runtime.state == State.CONNECTED) {
						runtime.tools = List.copyOf(tools);
					}
				}
			} catch (Exception ignored) {
				// Keep the last usable catalog when a list-changed refresh fails.
			}
		});
	}

	private ServerStatus snapshot(Runtime runtime) {
		synchronized (runtime.lock) {
			int enabledToolCount = (int) runtime.tools.stream()
					.filter(tool -> !runtime.disabledTools.contains(tool.name()))
					.count();
			return new ServerStatus(
					runtime.name,
					runtime.state,
					runtime.message,
					runtime.tools.size(),
					enabledToolCount,
					target(runtime.config),
					runtime.authorizationUrl);
		}
	}

	private Runtime require(String name) {
		Runtime runtime = servers.get(name);
		if (runtime == null) throw new IllegalArgumentException("MCP server is not configured: " + name);
		return runtime;
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		for (Runtime runtime : servers.values()) {
			McpClient client;
			Thread connector;
			synchronized (runtime.lock) {
				runtime.generation++;
				connector = runtime.connector;
				runtime.connector = null;
				client = runtime.client;
				runtime.client = null;
				runtime.tools = List.of();
				runtime.state = State.DISABLED;
				runtime.authorizationUrl = null;
			}
			if (connector != null) connector.interrupt();
			if (client != null) client.close();
		}
	}

	private static String target(McpServerConfig config) {
		if (config instanceof McpServerConfig.Local local) return String.join(" ", local.command());
		return ((McpServerConfig.Remote) config).url().toString();
	}

	private static String message(Exception error) {
		String value = error.getMessage();
		if (value == null || value.isBlank()) value = error.toString();
		return value.replaceAll("\\s+", " ").trim();
	}

	private static final class Runtime {
		final Object lock = new Object();
		final String name;
		final McpServerConfig config;
		long generation;
		State state;
		String message;
		McpClient client;
		List<McpClient.ToolDefinition> tools = List.of();
		final Set<String> disabledTools = new HashSet<>();
		Thread connector;
		String authorizationUrl;

		Runtime(String name, McpServerConfig config) {
			this.name = name;
			this.config = config;
			this.state = config.enabled() ? State.CONNECTING : State.DISABLED;
		}
	}
}
