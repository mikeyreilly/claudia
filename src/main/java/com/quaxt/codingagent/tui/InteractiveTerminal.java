package com.quaxt.codingagent.tui;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

/**
 * State of the JLine terminal facade: the terminal and line reader, the
 * retained main-screen document, the status bar, the palette, and the
 * suspend/resume bookkeeping. Line editing, full-screen hosting, status
 * rendering, and the JLine adapters live in CodingAgentOperations.
 */
public final class InteractiveTerminal {
	/** Semantic accent for the high-priority activity segment of the status bar. */
	public enum StatusAccent {
		NONE,
		READY,
		ACTIVE,
		TOOL,
		WARNING
	}

	public static final int DEFAULT_COLUMNS = 80;
	public static final int DEFAULT_ROWS = 24;
	public static final String BEGIN_SYNCHRONIZED_OUTPUT = "\u001b[?2026h";
	public static final String END_SYNCHRONIZED_OUTPUT = "\u001b[?2026l";
	public static final String CLEAR_SCREEN_AND_SCROLLBACK = "\u001b[2J\u001b[H\u001b[3J";
	public static final String SECONDARY_PROMPT = "%M> ";
	public static final String BRACKETED_PASTE_END = "\u001b[201~";
	public static final long PASTE_LOOKAHEAD_MILLIS = 10;

	public Terminal terminal;
	public LineReaderImpl reader;
	public Callable<Void> suspendAction;
	public boolean supportsSuspend;
	public Attributes shellAttributes;
	public Terminal.SignalHandler previousContinueHandler;
	public Terminal.SignalHandler previousResizeHandler;
	public StringBuilder screenDocument = new StringBuilder();
	public Status statusBar;
	public String statusActivity;
	public StatusAccent statusAccent = StatusAccent.NONE;
	public String statusLeft;
	public String statusRight;
	public Attributes fullScreenResumeAttributes;
	public volatile boolean managedSuspend;
	public volatile Theme theme = Theme.DARK;
	public String suspendedBuffer;
	public int suspendedCursor = -1;
	public int restoreCursor = -1;
	public CommandSuggestions activeCommandSuggestions;
	/** Post-prompt region consulted by the JLine reader adapter; null hides it. */
	public Supplier<AttributedString> dynamicPost;

	public InteractiveTerminal() {}
}
