package com.quaxt.codingagent.tui;

import com.quaxt.codingagent.terminal.Terminal;
import java.util.concurrent.Callable;

/**
 * State of the alternate-screen host: the terminal it draws on, the optional
 * suspend hook, the main-screen repaint hook, and the diffing renderer. The
 * event loop and the resize, mouse, and suspend/resume lifecycle live in
 * CodingAgentCli.
 */
public final class TuiRuntime {
	public static final int DEFAULT_COLUMNS = Terminal.DEFAULT_COLUMNS;
	public static final int DEFAULT_ROWS = Terminal.DEFAULT_ROWS;

	public Terminal terminal;
	/** Null when the host platform cannot suspend the process group. */
	public Callable<Void> suspendAction;
	public Runnable resumeMainScreen;
	public AnsiRenderer renderer = new AnsiRenderer();
	/** The mode to restore when leaving the alternate screen. */
	public Terminal.Mode originalMode;
	/** Whether bracketed paste was enabled before the alternate screen. */
	public boolean pasteWasEnabled;
	public boolean active;

	public TuiRuntime(Terminal terminal, Callable<Void> suspendAction, Runnable resumeMainScreen) {
		this.terminal = terminal;
		this.suspendAction = suspendAction;
		this.resumeMainScreen = resumeMainScreen;
	}
}
