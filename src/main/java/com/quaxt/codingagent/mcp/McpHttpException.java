package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP failure that retains the authentication challenge returned by an MCP server. */
final class McpHttpException extends IOException {
	private final int status;
	private final URI uri;
	private final Map<String, List<String>> headers;
	private final String body;

	McpHttpException(int status, URI uri, Map<String, List<String>> headers, String body, String message) {
		super(message);
		this.status = status;
		this.uri = uri;
		LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
		headers.forEach((name, values) -> copied.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
		this.headers = Map.copyOf(copied);
		this.body = body == null ? "" : body;
	}

	int status() {
		return status;
	}

	URI uri() {
		return uri;
	}

	String body() {
		return body;
	}

	String header(String name) {
		List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
		return values == null || values.isEmpty() ? null : String.join(", ", values);
	}

	Map<String, List<String>> headers() {
		return headers;
	}

	static McpHttpException find(Throwable error) {
		return find(error, new ArrayList<>());
	}

	private static McpHttpException find(Throwable error, List<Throwable> visited) {
		if (error == null || visited.contains(error)) return null;
		visited.add(error);
		if (error instanceof McpHttpException http) return http;
		McpHttpException cause = find(error.getCause(), visited);
		if (cause != null) return cause;
		for (Throwable suppressed : error.getSuppressed()) {
			McpHttpException found = find(suppressed, visited);
			if (found != null) return found;
		}
		return null;
	}
}
