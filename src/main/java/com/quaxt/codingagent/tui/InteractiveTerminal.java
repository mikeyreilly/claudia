package com.quaxt.codingagent.tui;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** JLine terminal facade with a retained main-screen document and full-screen component support. */
public final class InteractiveTerminal implements AutoCloseable {
	private static final int DEFAULT_COLUMNS = 80;
	private static final int DEFAULT_ROWS = 24;
	private static final String BEGIN_SYNCHRONIZED_OUTPUT = "\u001b[?2026h";
	private static final String END_SYNCHRONIZED_OUTPUT = "\u001b[?2026l";
	private static final String CLEAR_SCREEN_AND_SCROLLBACK = "\u001b[2J\u001b[H\u001b[3J";
	private static final String CLEAR_TO_END_OF_LINE = "\u001b[K";
	private static final String SECONDARY_PROMPT = "%M> ";
	private static final String BRACKETED_PASTE_END = "\u001b[201~";
	private static final long PASTE_LOOKAHEAD_MILLIS = 10;

	private final Terminal terminal;
	private final LineReaderImpl reader;
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
		ensureUsableSize(terminal);
		this.suspendAction = suspendAction;
		this.supportsSuspend = supportsSuspend;
		shellAttributes = new Attributes(terminal.getAttributes());
		reader = (LineReaderImpl) LineReaderBuilder.builder().terminal(terminal).build();
		installEditorBindings();
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

	private static void ensureUsableSize(Terminal terminal) {
		int columns = terminal.getColumns();
		int rows = terminal.getRows();
		if (columns <= 0 || rows <= 0) {
			terminal.setSize(org.jline.terminal.Size.of(
					columns > 0 ? columns : DEFAULT_COLUMNS,
					rows > 0 ? rows : DEFAULT_ROWS));
		}
	}

	private void installEditorBindings() {
		String newlineWidgetName = "codingagent-insert-newline";
		reader.getWidgets().put(newlineWidgetName, () -> {
			reader.getBuffer().write('\n');
			return true;
		});
		Reference insertNewline = new Reference(newlineWidgetName);
		String[] newlineSequences = Keybindings.editorSequences("newline").toArray(String[]::new);

		String submitWidgetName = "codingagent-submit-or-insert-pasted-newline";
		reader.getWidgets().put(submitWidgetName, this::submitOrInsertPastedNewline);
		Reference submit = new Reference(submitWidgetName);
		String[] submitSequences = Keybindings.editorSequences("submit").toArray(String[]::new);

		reader.getWidgets().put(LineReader.BEGIN_PASTE, this::insertBracketedPaste);
		for (var keyMap : reader.getKeyMaps().values()) {
			keyMap.bind(insertNewline, newlineSequences);
			keyMap.bind(submit, submitSequences);
		}
	}

	/**
	 * Some consoles send pasted text without bracketed-paste markers. A pasted
	 * line ending is followed immediately by more input, unlike a submit key.
	 */
	private boolean submitOrInsertPastedNewline() {
		int next = reader.peekCharacter(PASTE_LOOKAHEAD_MILLIS);
		if (next >= 0) {
			if (next == '\n') reader.readCharacter();
			reader.getBuffer().write('\n');
			return true;
		}
		reader.callWidget(LineReader.ACCEPT_LINE);
		return true;
	}

	/** JLine maps every CR in a bracketed paste to LF, doubling CRLF input. */
	private boolean insertBracketedPaste() {
		StringBuilder content = new StringBuilder();
		while (true) {
			int value = reader.readCharacter();
			if (value < 0) break;
			content.append((char) value);
			if (endsWith(content, BRACKETED_PASTE_END)) {
				content.setLength(content.length() - BRACKETED_PASTE_END.length());
				break;
			}
		}
		reader.getBuffer().write(normalizeLineEndings(content.toString()));
		return true;
	}

	private static boolean endsWith(StringBuilder value, String suffix) {
		if (value.length() < suffix.length()) return false;
		int offset = value.length() - suffix.length();
		for (int index = 0; index < suffix.length(); index++) {
			if (value.charAt(offset + index) != suffix.charAt(index)) return false;
		}
		return true;
	}

	private static String normalizeLineEndings(String value) {
		return value.replace("\r\n", "\n").replace('\r', '\n');
	}

	/** Returns null on EOF and an empty string after Ctrl-C. */
	public String readLine(String prompt) {
		return readLine(prompt, null);
	}

	/** Returns null on EOF and an empty string after Ctrl-C without echoing the entered value. */
	public String readPassword(String prompt) {
		return readLine(prompt, '*');
	}

	private String readLine(String prompt, Character mask) {
		String initialBuffer = null;
		while (true) {
			Theme promptTheme = theme;
			try {
				String line = readEditorLine(prompt, mask, initialBuffer, promptTheme);
				rememberCompletedLine(prompt, line, mask, promptTheme);
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
				rememberCompletedLine(prompt, "", mask, promptTheme);
				return "";
			} catch (EndOfFileException ignored) {
				rememberPrompt(prompt, promptTheme);
				return null;
			}
		}
	}

	/** Keeps the background active so JLine's erase/edit operations preserve the full-width prompt bar. */
	private String readEditorLine(String prompt, Character mask, String initialBuffer, Theme promptTheme) {
		String background = promptTheme.promptBackground();
		reader.setVariable(
				LineReader.SECONDARY_PROMPT_PATTERN,
				background.isEmpty()
						? SECONDARY_PROMPT
						: hiddenForJLine(background + CLEAR_TO_END_OF_LINE) + SECONDARY_PROMPT);
		String editorPrompt = background.isEmpty()
				? prompt
				: styleActivePromptLine(prompt, background);
		try {
			return reader.readLine(editorPrompt, null, mask, initialBuffer);
		} finally {
			resetPromptBackground(promptTheme);
		}
	}

	private static String styleActivePromptLine(String prompt, String background) {
		int activeLineOffset = activePromptLineOffset(prompt);
		return prompt.substring(0, activeLineOffset)
				+ hiddenForJLine(background + CLEAR_TO_END_OF_LINE)
				+ prompt.substring(activeLineOffset);
	}

	private static String hiddenForJLine(String value) {
		return "%{" + value + "%}";
	}

	private static int activePromptLineOffset(String prompt) {
		return Math.max(prompt.lastIndexOf('\n'), prompt.lastIndexOf('\r')) + 1;
	}

	public <T> T run(TuiComponent<T> component) throws IOException {
		if (reader.isReading()) resetPromptBackground(theme);
		try {
			return new TuiRuntime(
					terminal, theme, supportsSuspend ? this::suspendFullScreen : null, this::resumeMainScreen)
					.run(component);
		} finally {
			if (reader.isReading()) reader.callWidget(LineReader.REDRAW_LINE);
		}
	}

	/**
	 * Runs an operation while listening for an interrupt key. The operation uses
	 * a virtual thread so Escape can be read even while it is blocked on a model
	 * response or tool. Ctrl-C remains an interrupt alias outside the line editor.
	 */
	public <T> T runInterruptibly(Callable<T> operation, Runnable interruptHandler)
			throws IOException, InterruptedException {
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(interruptHandler, "interruptHandler");
		Attributes originalAttributes = terminal.enterRawMode();
		FutureTask<T> task = new FutureTask<>(operation);
		Thread worker = Thread.ofVirtual().name("codingagent-interactive-operation").start(task);
		boolean interruptRequested = false;
		try {
			while (!task.isDone()) {
				TuiInput input = TuiInputReader.read(terminal.reader(), 50);
				if (input instanceof TuiInput.Key key
						&& (key.type() == TuiInput.KeyType.ESCAPE || key.type() == TuiInput.KeyType.CANCEL)
						&& !interruptRequested
						&& !task.isDone()) {
					interruptRequested = true;
					interruptHandler.run();
				} else if (input instanceof TuiInput.Key key
						&& key.type() == TuiInput.KeyType.SUSPEND
						&& supportsSuspend) {
					terminal.setAttributes(originalAttributes);
					managedSuspend = true;
					try {
						suspendAction.suspend();
					} catch (IOException error) {
						println("Could not suspend process: " + error.getMessage());
					} finally {
						repaintScreen();
						managedSuspend = false;
						terminal.enterRawMode();
					}
				}
			}
			return completedTask(task);
		} catch (IOException | RuntimeException | Error error) {
			if (!task.isDone()) {
				interruptHandler.run();
				worker.interrupt();
			}
			throw error;
		} finally {
			terminal.setAttributes(originalAttributes);
		}
	}

	private static <T> T completedTask(FutureTask<T> task) throws IOException, InterruptedException {
		try {
			return task.get();
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			if (cause instanceof InterruptedException interrupted) throw interrupted;
			if (cause instanceof IOException io) throw io;
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error fatal) throw fatal;
			throw new IllegalStateException(cause);
		}
	}

	/** Binds a configured application action while the line editor is active. */
	public void bindAppAction(String action, Runnable handler) {
		Objects.requireNonNull(handler, "handler");
		String widgetName = "codingagent-" + action;
		reader.getWidgets().put(widgetName, () -> {
			resetPromptBackground(theme);
			try {
				handler.run();
			} finally {
				reader.callWidget(LineReader.REDRAW_LINE);
			}
			return true;
		});
		Reference reference = new Reference(widgetName);
		for (var keyMap : reader.getKeyMaps().values()) {
			keyMap.bind(reference, Keybindings.appSequence(action));
		}
	}

	/** Prints a status line without losing the active line-editor buffer. */
	public synchronized void printAbove(String text) {
		if (reader.isReading()) resetPromptBackground(theme);
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

	private synchronized void rememberCompletedLine(
			String prompt, String line, Character mask, Theme promptTheme) {
		String displayedLine = line;
		if (mask != null) {
			displayedLine = mask.charValue() == 0 ? "" : String.valueOf(mask).repeat(line.length());
		}
		int activeLineOffset = activePromptLineOffset(prompt);
		remember(prompt.substring(0, activeLineOffset));
		remember(promptTheme.promptArea(prompt.substring(activeLineOffset) + displayedLine));
		remember(System.lineSeparator());
	}

	private synchronized void rememberPrompt(String prompt, Theme promptTheme) {
		int activeLineOffset = activePromptLineOffset(prompt);
		remember(prompt.substring(0, activeLineOffset));
		remember(promptTheme.promptArea(prompt.substring(activeLineOffset)));
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

	private void resetPromptBackground(Theme promptTheme) {
		if (promptTheme.promptBackground().isEmpty()) return;
		terminal.writer().print(promptTheme.reset());
		terminal.writer().flush();
	}

	private synchronized void repaintScreen() {
		terminal.writer().print(theme.reset());
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
