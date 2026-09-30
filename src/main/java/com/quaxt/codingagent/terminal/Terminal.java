package com.quaxt.codingagent.terminal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A terminal: one UTF-8 input path, UTF-8 output, a size, a line discipline
 * mode, and resize/continue notifications. This base class is backed by
 * streams, which tests use directly; {@link #system()} opens the host console.
 *
 * <p>Output features that change the terminal's state for other programs
 * (keyboard-protocol flags, alternate screen, mouse reporting, bracketed paste,
 * hidden cursor, and scroll margins) go through methods here so that
 * {@link #close()} can undo whatever is still active.
 */
public class Terminal implements Closeable {
    public static final int DEFAULT_COLUMNS = 80;
    public static final int DEFAULT_ROWS = 24;
    public static final String TYPE_DUMB = "dumb";
    public static final String TYPE_DUMB_COLOR = "dumb-color";

    public enum Signal {
        /** The window size changed. */
        WINCH,
        /** The process resumed after being stopped. */
        CONT
    }

    public record Size(int columns, int rows) {}

    /**
     * An opaque line-discipline state. {@link #raw()} reports whether canonical
     * input, echo, and signal generation are disabled.
     */
    public static final class Mode {
        private final boolean raw;
        final byte[] state;

        Mode(boolean raw, byte[] state) {
            this.raw = raw;
            this.state = state;
        }

        public boolean raw() {
            return raw;
        }
    }

    private final String type;
    private final OutputStream out;
    private final InputReader input;
    private final Object outputLock = new Object();
    /**
     * Guards changing the mode and applying it to the line discipline. Resuming
     * after a stop reapplies the mode from a signal thread while the main thread
     * may be restoring its own; without this the signal thread could read the old
     * mode and apply it after the new one, leaving the tty cooked while this
     * terminal reports raw.
     */
    private final Object modeLock = new Object();
    private final Map<Signal, List<Runnable>> handlers = new ConcurrentHashMap<>();
    private final Mode initialMode;
    private volatile Size size;
    private volatile Mode mode;
    /** Kitty keyboard flags pushed on the main and alternate screens, which keep separate stacks. */
    private int mainKeyboardFlags;
    private int alternateKeyboardFlags;
    private int synchronizedDepth;
    private boolean alternateScreen;
    private boolean mouseTracking;
    private boolean bracketedPaste;
    private boolean cursorHidden;
    private boolean scrollRegion;
    private boolean closed;

    protected Terminal(String type, InputStream in, OutputStream out, Size size, Mode initialMode) {
        this.type = type;
        this.out = out;
        this.input = new InputReader(in);
        this.size = size;
        this.initialMode = initialMode;
        this.mode = initialMode;
    }

    /** A terminal over streams, starting in cooked mode. */
    public static Terminal streams(String type, InputStream in, OutputStream out, int columns, int rows) {
        return new Terminal(type, in, out, new Size(columns, rows), new Mode(false, null));
    }

    /**
     * The controlling terminal on standard input and output.
     *
     * @throws IOException with a user-facing message when the platform is unsupported,
     *     or standard input/output is not a terminal
     */
    public static Terminal system() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return WindowsTerminal.open();
        }
        return PosixTerminal.open();
    }

    public String type() {
        return type;
    }

    public InputReader input() {
        return input;
    }

    /** Writes and flushes text as UTF-8. Each call is written atomically with respect to other writers. */
    public void write(CharSequence text) {
        if (text.isEmpty()) return;
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (outputLock) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    /** The current size, substituting 80x24 for dimensions the terminal does not report. */
    public Size size() {
        Size current = querySize();
        int columns = current == null || current.columns() <= 0 ? DEFAULT_COLUMNS : current.columns();
        int rows = current == null || current.rows() <= 0 ? DEFAULT_ROWS : current.rows();
        return new Size(columns, rows);
    }

    public int columns() {
        return size().columns();
    }

    public int rows() {
        return size().rows();
    }

    /** Sets the reported size of a stream terminal; the tty reports its own size. */
    public void setSize(int columns, int rows) {
        size = new Size(columns, rows);
    }

    protected Size querySize() {
        return size;
    }

    public Mode mode() {
        return mode;
    }

    /** Enters raw mode and returns the previous mode for {@link #setMode(Mode)}. */
    public Mode enterRawMode() {
        synchronized (modeLock) {
            Mode previous = mode;
            if (!previous.raw()) setMode(rawVariant(previous));
            return previous;
        }
    }

    public void setMode(Mode next) {
        if (next == null) return;
        synchronized (modeLock) {
            if (next == mode) return;
            applyMode(next);
            mode = next;
        }
    }

    /** The mode in effect when this terminal was opened. */
    public Mode initialMode() {
        return initialMode;
    }

    protected Mode rawVariant(Mode cooked) {
        return new Mode(true, null);
    }

    protected void applyMode(Mode next) {
        // Stream terminals only track the mode.
    }

    /** Re-applies the tracked mode, for example after the shell changed it while the process was stopped. */
    protected void reapplyMode() {
        synchronized (modeLock) {
            applyMode(mode);
        }
    }

    /** Registers a handler; returns a handle that unregisters it. */
    public AutoCloseable handle(Signal signal, Runnable handler) {
        List<Runnable> list = handlers.computeIfAbsent(signal, ignored -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }

    /** Delivers a signal to the registered handlers on the calling thread. */
    public void raise(Signal signal) {
        for (Runnable handler : handlers.getOrDefault(signal, List.of())) handler.run();
    }

    // ------------------------------------------------------- tracked features

    /** Pushes kitty keyboard-protocol flags onto the current screen's stack. */
    public void pushKeyboardFlags(int flags) {
        synchronized (outputLock) {
            write(Ansi.pushKeyboardFlags(flags));
            if (alternateScreen) alternateKeyboardFlags++;
            else mainKeyboardFlags++;
        }
    }

    /** Pops flags this terminal pushed on the current screen; does nothing when there are none. */
    public void popKeyboardFlags() {
        synchronized (outputLock) {
            if ((alternateScreen ? alternateKeyboardFlags : mainKeyboardFlags) == 0) return;
            write(Ansi.POP_KEYBOARD_FLAGS);
            if (alternateScreen) alternateKeyboardFlags--;
            else mainKeyboardFlags--;
        }
    }

    public void alternateScreen(boolean enabled) {
        synchronized (outputLock) {
            if (!enabled && alternateScreen) {
                write(Ansi.POP_KEYBOARD_FLAGS.repeat(alternateKeyboardFlags));
                alternateKeyboardFlags = 0;
            }
            write(enabled ? Ansi.ALTERNATE_SCREEN_ON : Ansi.ALTERNATE_SCREEN_OFF);
            alternateScreen = enabled;
        }
    }

    public void mouseTracking(boolean enabled) {
        synchronized (outputLock) {
            if (enabled == mouseTracking) return;
            write(enabled ? Ansi.MOUSE_ON : Ansi.MOUSE_OFF);
            mouseTracking = enabled;
        }
    }

    public void bracketedPaste(boolean enabled) {
        synchronized (outputLock) {
            if (enabled == bracketedPaste) return;
            write(enabled ? Ansi.BRACKETED_PASTE_ON : Ansi.BRACKETED_PASTE_OFF);
            bracketedPaste = enabled;
        }
    }

    public boolean bracketedPaste() {
        synchronized (outputLock) {
            return bracketedPaste;
        }
    }

    public void cursorVisible(boolean visible) {
        synchronized (outputLock) {
            write(visible ? Ansi.CURSOR_SHOW : Ansi.CURSOR_HIDE);
            cursorHidden = !visible;
        }
    }

    /** Restricts scrolling to rows {@code top..bottom} (one-based), keeping the cursor in place. */
    public void scrollRegion(int top, int bottom) {
        synchronized (outputLock) {
            write(Ansi.SAVE_CURSOR + Ansi.scrollRegion(top, bottom) + Ansi.RESTORE_CURSOR);
            scrollRegion = true;
        }
    }

    public void resetScrollRegion() {
        synchronized (outputLock) {
            write(Ansi.SAVE_CURSOR + Ansi.RESET_SCROLL_REGION + Ansi.RESTORE_CURSOR);
            scrollRegion = false;
        }
    }

    /** Starts a synchronized update; nested updates are written as one. */
    public void beginUpdate() {
        synchronized (outputLock) {
            if (synchronizedDepth++ == 0) write(Ansi.BEGIN_SYNCHRONIZED_UPDATE);
        }
    }

    public void endUpdate() {
        synchronized (outputLock) {
            if (synchronizedDepth == 0) return;
            if (--synchronizedDepth == 0) write(Ansi.END_SYNCHRONIZED_UPDATE);
        }
    }

    /** Sequences that return the terminal to the state other programs expect. */
    protected String restoreSequence() {
        synchronized (outputLock) {
            StringBuilder sequence = new StringBuilder();
            if (synchronizedDepth > 0) sequence.append(Ansi.END_SYNCHRONIZED_UPDATE);
            if (mouseTracking) sequence.append(Ansi.MOUSE_OFF);
            if (bracketedPaste) sequence.append(Ansi.BRACKETED_PASTE_OFF);
            if (alternateScreen) {
                sequence.append(Ansi.POP_KEYBOARD_FLAGS.repeat(alternateKeyboardFlags));
                sequence.append(Ansi.ALTERNATE_SCREEN_OFF);
            }
            sequence.append(Ansi.POP_KEYBOARD_FLAGS.repeat(mainKeyboardFlags));
            if (scrollRegion) sequence.append(Ansi.SAVE_CURSOR + Ansi.RESET_SCROLL_REGION + Ansi.RESTORE_CURSOR);
            if (cursorHidden) sequence.append(Ansi.CURSOR_SHOW);
            if (!sequence.isEmpty()) sequence.append(Ansi.RESET);
            synchronizedDepth = 0;
            mouseTracking = false;
            bracketedPaste = false;
            mainKeyboardFlags = 0;
            alternateKeyboardFlags = 0;
            alternateScreen = false;
            scrollRegion = false;
            cursorHidden = false;
            return sequence.toString();
        }
    }

    public boolean isClosed() {
        synchronized (outputLock) {
            return closed;
        }
    }

    /** Undoes active output features and restores the initial mode. */
    @Override
    public void close() throws IOException {
        synchronized (outputLock) {
            if (closed) return;
            closed = true;
        }
        try {
            write(restoreSequence());
        } catch (UncheckedIOException error) {
            // The terminal may already be gone; still restore the mode.
        }
        setMode(initialMode);
        handlers.clear();
    }
}
