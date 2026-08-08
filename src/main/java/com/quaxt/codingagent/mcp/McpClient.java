package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** One initialized MCP client session and its cached tool catalog. */
final class McpClient implements AutoCloseable {
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
	private static final String PROTOCOL_VERSION = "2025-11-25";
	private static final int MAX_LIST_PAGES = 1_000;

	private final McpTransport transport;
	private final Duration timeout;
	private final ObjectNode capabilities;
	private final String instructions;

	private McpClient(McpTransport transport, Duration timeout, ObjectNode capabilities, String instructions) {
		this.transport = transport;
		this.timeout = timeout;
		this.capabilities = capabilities;
		this.instructions = instructions;
	}

	static McpClient connect(String serverName, McpServerConfig config, Path workspace) throws Exception {
		Duration timeout = config.timeoutMillis() == null
				? DEFAULT_TIMEOUT
				: Duration.ofMillis(config.timeoutMillis());
		if (config instanceof McpServerConfig.Local local) {
			McpTransport transport = new StdioMcpTransport(local, workspace);
			return initializeOwned(transport, timeout);
		}

		McpServerConfig.Remote remote = McpOAuthCredentials.apply(serverName, (McpServerConfig.Remote) config);
		Exception streamableFailure;
		McpTransport streamable = new StreamableHttpMcpTransport(remote, workspace);
		try {
			return initializeOwned(streamable, timeout);
		} catch (Exception error) {
			streamableFailure = error;
			if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw error;
		}
		McpTransport sse = new SseHttpMcpTransport(remote, workspace);
		try {
			return initializeOwned(sse, timeout);
		} catch (Exception error) {
			error.addSuppressed(streamableFailure);
			throw error;
		}
	}

	private static McpClient initializeOwned(McpTransport transport, Duration timeout) throws Exception {
		try {
			ObjectNode params = Json.object().put("protocolVersion", PROTOCOL_VERSION);
			params.putObject("capabilities").putObject("roots");
			params.putObject("clientInfo").put("name", "codingagent").put("version", "0.1.0-java");
			JsonNode resultNode = transport.request("initialize", params, timeout, null);
			if (!(resultNode instanceof ObjectNode result)) {
				throw new IOException("MCP initialize returned no result object");
			}
			String negotiated = result.path("protocolVersion").isTextual()
					? result.path("protocolVersion").asText()
					: PROTOCOL_VERSION;
			transport.protocolVersion(negotiated);
			ObjectNode capabilities = result.path("capabilities") instanceof ObjectNode object
					? object.deepCopy()
					: Json.object();
			String instructions = result.path("instructions").isTextual()
					? result.path("instructions").asText().trim()
					: null;
			transport.notify("notifications/initialized", Json.object());
			return new McpClient(transport, timeout, capabilities, instructions);
		} catch (Exception error) {
			transport.close();
			throw error;
		}
	}

	List<ToolDefinition> listTools() throws Exception {
		if (!capabilities.has("tools")) return List.of();
		List<ToolDefinition> result = new ArrayList<>();
		Set<String> cursors = new HashSet<>();
		String cursor = null;
		for (int page = 0; page < MAX_LIST_PAGES; page++) {
			ObjectNode params = Json.object();
			if (cursor != null) params.put("cursor", cursor);
			JsonNode response = transport.request("tools/list", params, timeout, null);
			if (response == null || !response.isObject() || !response.path("tools").isArray()) {
				throw new IOException("MCP tools/list returned an invalid result");
			}
			for (JsonNode tool : response.path("tools")) result.add(parseTool(tool));
			JsonNode next = response.get("nextCursor");
			if (next == null || next.isNull()) return List.copyOf(result);
			if (!next.isTextual()) throw new IOException("MCP tools/list nextCursor must be a string");
			cursor = next.asText();
			if (!cursors.add(cursor)) throw new IOException("MCP tools/list returned duplicate cursor: " + cursor);
		}
		throw new IOException("MCP tools/list exceeded " + MAX_LIST_PAGES + " pages");
	}

	CallResult callTool(String name, ObjectNode arguments, AbortSignal signal) throws Exception {
		ObjectNode params = Json.object().put("name", name);
		params.set("arguments", arguments == null ? Json.object() : arguments);
		JsonNode response = transport.request("tools/call", params, timeout, signal);
		if (!(response instanceof ObjectNode object)) {
			throw new IOException("MCP tools/call returned an invalid result");
		}
		return new CallResult(object.deepCopy(), object.path("isError").asBoolean(false));
	}

	void onNotification(BiConsumer<String, JsonNode> listener) {
		transport.onNotification(listener);
	}

	String instructions() {
		return instructions;
	}

	@Override
	public void close() {
		transport.close();
	}

	private static ToolDefinition parseTool(JsonNode node) throws IOException {
		if (!node.isObject() || !node.path("name").isTextual() || node.path("name").asText().isBlank()) {
			throw new IOException("MCP tools/list returned a tool without a name");
		}
		String description = node.path("description").isTextual() ? node.path("description").asText() : "";
		ObjectNode inputSchema = node.path("inputSchema") instanceof ObjectNode object
				? object.deepCopy()
				: Json.object();
		inputSchema.put("type", "object");
		if (!(inputSchema.get("properties") instanceof ObjectNode)) inputSchema.set("properties", Json.object());
		// Match OpenCode's dynamic MCP tool conversion. It keeps schemas bounded
		// and avoids providers rejecting unspecified object properties.
		inputSchema.put("additionalProperties", false);
		return new ToolDefinition(node.path("name").asText(), description, inputSchema);
	}

	record ToolDefinition(String name, String description, ObjectNode inputSchema) {}

	record CallResult(ObjectNode raw, boolean isError) {}
}
