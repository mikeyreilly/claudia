package com.quaxt.codingagent.tui;

import java.io.IOException;
import java.util.Objects;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** JLine terminal facade with a retained main-screen document and full-screen component support. */
public final class InteractiveTerminal implements AutoCloseable {
	private static final String BEGIN_SYNCHRONIZED_OUTPUT = "\u001b[?2026h";
	private static final String END_SYNCHRONIZED_OUTPUT = "\u001b[?2026l";
	private static final String CLEAR_SCREEN_AND_SCROLLBACK = "\u001b[2J\u001b[H\u001b[3J";

	private final Terminal terminal;
	private final LineReader reader;
	private final SuspendAction suspendAction;
	private final boolean supportsSuspend;
	private final Attributes shellAttributes;
	private final Terminal.SignalHandler previousContinueHandler;
	private final StringBuilder screenDocument = new StringBuilder();
	private Attributes fullScreenResumeAttributes;
	private volatile boolean managedSuspend;
	private Theme theme;
	private String suspendedBuffer;
	private int suspendedCursor = -1;
	private int restoreCursor = -1;

	public InteractiveTerminal(String appName) throws IOException {
		this(
				TerminalBuilder.builder().system(true).name(appName).build(),
				ProcessSuspender::suspend,
				!System.getProperty("os.name").startsWith("Windows"));
	}

	InteractiveTerminal(Terminal terminal, SuspendAction suspendAction, boolean supportsSuspend) {
		this.terminal = terminal;
		this.suspendAction = suspendAction;
		this.supportsSuspend = supportsSuspend;
		shellAttributes = new Attributes(terminal.getAttributes());
		reader = LineReaderBuilder.builder().terminal(terminal).build();
		previousContinueHandler = supportsSuspend
				? terminal.handle(Terminal.Signal.CONT, this::handleContinue)
				: null;
		if (supportsSuspend) {
			Widget previousInit = reader.getWidgets().get(LineReader.CALLBACK_INIT);
			reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
				boolean initialized = previousInit == null || previousInit.apply();
				if (restoreCursor >= 0) {
					reader.getBuffer().cursor(Math.min(restoreCursor, reader.getBuffer().length()));
					restoreCursor = -1;
				}
				return initialized;
			});
			reader.getWidgets().put("suspend-process", this::requestSuspend);
			Reference suspend = new Reference("suspend-process");
			for (var keyMap : reader.getKeyMaps().values()) {
				keyMap.bind(suspend, Keybindings.appSequence("suspend"));
			}
		}
		theme = Theme.DARK;
	}

	/** Returns null on EOF and an empty string after Ctrl-C. */
	public String readLine(String prompt) {
		String initialBuffer = null;
		while (true) {
			try {
				String line = reader.readLine(prompt, null, (Character) null, initialBuffer);
				rememberCompletedLine(prompt, line);
				return line;
			} catch (SuspendRequested ignored) {
				initialBuffer = suspendedBuffer;
				restoreCursor = suspendedCursor;
				managedSuspend = true;
				try {
					suspendAction.suspend();
				} catch (IOException error) {
					println("Could not suspend process: " + error.getMessage());
				} finally {
					repaintScreen();
					managedSuspend = false;
				}
			} catch (UserInterruptException ignored) {
				rememberCompletedLine(prompt, "");
				return "";
			} catch (EndOfFileException ignored) {
				remember(prompt);
				return null;
			}
		}
	}

	public <T> T run(TuiComponent<T> component) throws IOException {
		try {
			return new TuiRuntime(
					terminal, theme, supportsSuspend ? this::suspendFullScreen : null, this::resumeMainScreen)
					.run(component);
		} finally {
			if (reader.isReading()) reader.callWidget(LineReader.REDRAW_LINE);
		}
	}

	/** Binds a configured application action while the line editor is active. */
	public void bindAppAction(String action, Runnable handler) {
		Objects.requireNonNull(handler, "handler");
		String widgetName = "codingagent-" + action;
		reader.getWidgets().put(widgetName, () -> {
			handler.run();
			return true;
		});
		Reference reference = new Reference(widgetName);
		for (var keyMap : reader.getKeyMaps().values()) {
			keyMap.bind(reference, Keybindings.appSequence(action));
		}
	}

	/** Prints a status line without losing the active line-editor buffer. */
	public synchronized void printAbove(String text) {
		reader.printAbove(text);
		remember(text + System.lineSeparator());
	}

	public synchronized void print(String text) {
		String value = String.valueOf(text);
		terminal.writer().print(value);
		terminal.writer().flush();
		remember(value);
	}

	public synchronized void println(String text) {
		String value = String.valueOf(text);
		terminal.writer().println(value);
		terminal.writer().flush();
		remember(value + System.lineSeparator());
	}

	/** Replaces the main-screen document and redraws it from the top. */
	public synchronized void replaceScreen(String document) {
		screenDocument.setLength(0);
		screenDocument.append(document == null ? "" : document);
		repaintScreen();
	}

	public Theme theme() {
		return theme;
	}

	public void setTheme(Theme theme) {
		this.theme = theme;
	}

	private boolean requestSuspend() {
		suspendedBuffer = reader.getBuffer().toString();
		suspendedCursor = reader.getBuffer().cursor();
		throw SuspendRequested.INSTANCE;
	}

	private void handleContinue(Terminal.Signal signal) {
		try {
			if (previousContinueHandler != null
					&& previousContinueHandler != Terminal.SignalHandler.SIG_DFL
					&& previousContinueHandler != Terminal.SignalHandler.SIG_IGN) {
				previousContinueHandler.handle(signal);
			}
		} finally {
			if (!managedSuspend) repaintScreen();
		}
	}

	private synchronized void rememberCompletedLine(String prompt, String line) {
		remember(prompt + line + System.lineSeparator());
	}

	private synchronized void remember(String text) {
		screenDocument.append(text);
	}

	private void suspendFullScreen() throws IOException {
		fullScreenResumeAttributes = new Attributes(terminal.getAttributes());
		terminal.setAttributes(shellAttributes);
		managedSuspend = true;
		try {
			suspendAction.suspend();
		} catch (IOException | RuntimeException | Error error) {
			restoreFullScreenAttributes();
			managedSuspend = false;
			throw error;
		}
	}

	private synchronized void resumeMainScreen() {
		try {
			repaintScreen();
		} finally {
			restoreFullScreenAttributes();
			managedSuspend = false;
		}
	}

	private void restoreFullScreenAttributes() {
		if (fullScreenResumeAttributes != null) {
			terminal.setAttributes(fullScreenResumeAttributes);
			fullScreenResumeAttributes = null;
		}
	}

	private synchronized void repaintScreen() {
		terminal.writer().print(BEGIN_SYNCHRONIZED_OUTPUT);
		terminal.writer().print(CLEAR_SCREEN_AND_SCROLLBACK);
		terminal.writer().print(screenDocument);
		terminal.writer().print(END_SYNCHRONIZED_OUTPUT);
		terminal.writer().flush();
	}

	@Override
	public void close() throws IOException {
		if (previousContinueHandler != null) {
			terminal.handle(Terminal.Signal.CONT, previousContinueHandler);
		}
		terminal.close();
	}

	private static final class SuspendRequested extends RuntimeException {
		private static final SuspendRequested INSTANCE = new SuspendRequested();

		private SuspendRequested() {
			super(null, null, false, false);
		}
	}
}
