package com.quaxt.codingagent.cli;

import com.quaxt.codingagent.CodingAgentOperations;

/**
 * State of the JSONL stdin/stdout automation protocol: the configured
 * providers, CLI arguments, MCP manager, live agent, and session recorder.
 * The protocol loop, command handlers, and event encoding live in
 * CodingAgentOperations.
 */
public final class RpcServer {
	public CodingAgentOperations providers;
	public CodingAgentOperations arguments;
	public CodingAgentOperations mcp;
	public CodingAgentOperations agent;
	public CodingAgentOperations recorder;

	public RpcServer() {}
}
