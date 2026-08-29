package com.quaxt.codingagent.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.ai.CoreProviders;
import com.quaxt.codingagent.cli.session.SessionRecorder;
import com.quaxt.codingagent.cli.settings.SettingsStore;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.tui.InteractiveTerminal;

/**
 * State of the interactive shell: the terminal, agent, session recorder,
 * settings, MCP manager, streaming bookkeeping, and the live status bar.
 * The command loop, rendering, and every command live in
 * CodingAgentOperations.
 */
public final class InteractiveShell {
	public static final List<String> SLASH_COMMANDS = List.of(
			"/compact",
			"/details",
			"/exit",
			"/fork",
			"/help",
			"/login",
			"/logout",
			"/mcp",
			"/models",
			"/quit",
			"/resume",
			"/settings",
			"/theme");

	/** Which kind of streamed block is currently on the main screen. */
	public enum StreamOutput {
		NONE,
		THINKING,
		TEXT
	}

	public CoreProviders providers;
	public CodingAgentOperations cli;
	public InteractiveTerminal terminal;
	public SettingsStore settingsStore;
	public McpManager mcp;
	public SettingsStore.Settings settings;
	public Agent agent;
	public SessionRecorder recorder;
	public String sessionName;
	public Path cwd = Path.of(".").toAbsolutePath().normalize();
	public boolean emittedText;
	public boolean hideThinkingBlock;
	public StreamOutput streamOutput = StreamOutput.NONE;
	public int streamedThinkingCharacters;
	public Object activityLock = new Object();
	public ScheduledExecutorService statusTicker;
	public volatile ActivityStatus activity;
	public volatile String statusLocation = "";
	public volatile String statusModel = "";

	public InteractiveShell() {}
}
