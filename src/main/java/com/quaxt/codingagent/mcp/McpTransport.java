package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.function.BiConsumer;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** Internal JSON-RPC transport used by an MCP client session. */
interface McpTransport extends AutoCloseable {
	JsonNode request(String method, ObjectNode params, Duration timeout, AbortSignal signal) throws Exception;

	void notify(String method, ObjectNode params) throws Exception;

	default void protocolVersion(String version) {}

	default void onNotification(BiConsumer<String, JsonNode> listener) {}

	@Override
	void close();
}
