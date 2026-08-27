package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * HTTP failure that retains the authentication challenge returned by an MCP
 * server. Header lookup and cause scanning live in CodingAgentOperations; the
 * header map is stored with lower-case names by its creator.
 */
public final class McpHttpException extends IOException {
	public int status;
	public URI uri;
	public Map<String, List<String>> headers;
	public String body;

	public McpHttpException(int status, URI uri, Map<String, List<String>> headers, String body, String message) {
		super(message);
		this.status = status;
		this.uri = uri;
		this.headers = headers;
		this.body = body;
	}
}
