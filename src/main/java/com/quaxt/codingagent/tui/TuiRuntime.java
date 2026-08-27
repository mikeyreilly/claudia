package com.quaxt.codingagent.tui;

import java.util.concurrent.Callable;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

/**
 * State of the alternate-screen host: the terminal it draws on, the palette it
 * renders with, the optional suspend hook, the main-screen repaint hook, and
 * the diffing renderer. The event loop and the resize, mouse, and
 * suspend/resume lifecycle live in CodingAgentOperations.
 */
public final class TuiRuntime {
	public static final int DEFAULT_COLUMNS = 80;
	public static final int DEFAULT_ROWS = 24;

	public Terminal terminal;
	public Theme theme;
	/** Null when the host platform cannot suspend the process group. */
	public Callable<Void> suspendAction;
	public Runnable resumeMainScreen;
	public AnsiRenderer renderer = new AnsiRenderer();
	public Attributes originalAttributes;
	public boolean active;

	public TuiRuntime(Terminal terminal, Theme theme, Callable<Void> suspendAction, Runnable resumeMainScreen) {
		this.terminal = terminal;
		this.theme = theme;
		this.suspendAction = suspendAction;
		this.resumeMainScreen = resumeMainScreen;
	}
}
