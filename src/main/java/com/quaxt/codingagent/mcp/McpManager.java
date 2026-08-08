package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.quaxt.codingagent.agent.AgentTool;

/** Owns configured MCP sessions and supports runtime connect/disconnect toggles. */
public final class McpManager implements AutoCloseable {
	public enum State {
		CONNECTING,
		CONNECTED,
		DISABLED,
		FAILED
	}

	public record ServerStatus(
			String name,
			State state,
			String message,
			int toolCount,
			String target) {}

	private final Path workspace;
	private final LinkedHashMap<String, Runtime> servers = new LinkedHashMap<>();
	private volatile boolean closed;

	public McpManager(McpConfiguration configuration, Path workspace) {
		this.workspace = workspace.toAbsolutePath().normalize();
		configuration.servers().forEach((name, config) -> servers.put(name, new Runtime(name, config)));
		for (Runtime runtime : servers.values()) {
			if (runtime.config.enabled()) startConnect(runtime);
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
		Thread thread = startConnect(runtime);
		if (thread != null) thread.join();
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
		return state == State.CONNECTED || state == State.CONNECTING ? disconnect(name) : connect(name);
	}

	/** Returns the current model-visible tool adapters, with OpenCode-compatible names. */
	public List<AgentTool> tools() {
		LinkedHashMap<String, AgentTool> result = new LinkedHashMap<>();
		for (Runtime runtime : servers.values()) {
			McpClient client;
			List<McpClient.ToolDefinition> definitions;
			synchronized (runtime.lock) {
				if (runtime.state != State.CONNECTED || runtime.client == null) continue;
				client = runtime.client;
				definitions = runtime.tools;
			}
			for (McpClient.ToolDefinition definition : definitions) {
				McpAgentTool tool = new McpAgentTool(runtime.name, definition, client);
				result.put(tool.name(), tool);
			}
		}
		return List.copyOf(result.values());
	}

	private Thread startConnect(Runtime runtime) {
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
			generation = ++runtime.generation;
			connector = Thread.ofVirtual()
					.name("mcp-connect-" + McpAgentTool.sanitize(runtime.name))
					.unstarted(() -> connectAttempt(runtime, generation));
			runtime.connector = connector;
		}
		if (previousConnector != null && previousConnector != connector) previousConnector.interrupt();
		if (previous != null) previous.close();
		connector.start();
		return connector;
	}

	private void connectAttempt(Runtime runtime, long generation) {
		McpClient candidate = null;
		try {
			candidate = McpClient.connect(runtime.name, runtime.config, workspace);
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
				candidate = null;
			}
		} catch (Exception error) {
			synchronized (runtime.lock) {
				if (!closed && runtime.generation == generation) {
					runtime.state = State.FAILED;
					runtime.message = message(error);
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
			return new ServerStatus(
					runtime.name,
					runtime.state,
					runtime.message,
					runtime.tools.size(),
					target(runtime.config));
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
		Thread connector;

		Runtime(String name, McpServerConfig config) {
			this.name = name;
			this.config = config;
			this.state = config.enabled() ? State.CONNECTING : State.DISABLED;
		}
	}
}
