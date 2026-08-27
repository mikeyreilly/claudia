package com.quaxt.codingagent.cli;

import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.ai.CoreProviders;
import com.quaxt.codingagent.cli.session.SessionRecorder;
import com.quaxt.codingagent.mcp.McpManager;

/**
 * State of the JSONL stdin/stdout automation protocol: the configured
 * providers, CLI arguments, MCP manager, live agent, and session recorder.
 * The protocol loop, command handlers, and event encoding live in
 * CodingAgentOperations.
 */
public final class RpcServer {
	public CoreProviders providers;
	public Cli arguments;
	public McpManager mcp;
	public Agent agent;
	public SessionRecorder recorder;

	public RpcServer() {}
}
