package com.quaxt.codingagent.terminal;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The POSIX tty on macOS and Linux, controlled through termios and ioctl via
 * the Java foreign-function API. Raw mode disables canonical input, echo,
 * extended input processing, signal generation, and XON/XOFF and CR/NL input
 * translation; output processing stays on so newlines still return the carriage.
 */
final class PosixTerminal extends Terminal {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    // struct termios: macOS uses unsigned long flags and 20 control characters;
    // glibc and musl on x86_64/aarch64 use unsigned int flags, c_line, and 32.
    private static final int FLAG_BYTES = MAC ? 8 : 4;
    private static final int TERMIOS_BYTES = MAC ? 72 : 60;
    private static final long IFLAG = 0;
    private static final long OFLAG = FLAG_BYTES;
    private static final long LFLAG = 3L * FLAG_BYTES;
    private static final int CONTROL_CHARS = MAC ? 32 : 17;
    private static final int VTIME = MAC ? 17 : 5;
    private static final int VMIN = MAC ? 16 : 6;
    private static final long ISIG = MAC ? 0x80 : 0x1;
    private static final long ICANON = MAC ? 0x100 : 0x2;
    private static final long ECHO = 0x8;
    private static final long IEXTEN = MAC ? 0x400 : 0x8000;
    private static final long IXON = MAC ? 0x200 : 0x400;
    private static final long ICRNL = 0x100;
    private static final long INLCR = 0x40;
    private static final long TIOCGWINSZ = MAC ? 0x40087468L : 0x5413L;
    private static final int TCSANOW = 0;

    private final int fd;
    private final Map<Signal, sun.misc.SignalHandler> previousHandlers = new EnumMap<>(Signal.class);
    private final Thread shutdownHook;

    private PosixTerminal(String type, int fd, InputStream in, OutputStream out, Mode initialMode) {
        super(type, in, out, null, initialMode);
        this.fd = fd;
        this.shutdownHook = new Thread(this::restoreForExit, "codingagent-terminal-restore");
    }

    static Terminal open() throws IOException {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "unknown");
        String lowerOs = os.toLowerCase(Locale.ROOT);
        boolean supportedOs = lowerOs.startsWith("mac") || lowerOs.startsWith("linux");
        boolean supportedArch = arch.equals("aarch64") || arch.equals("arm64") || arch.equals("amd64") || arch.equals("x86_64");
        if (!supportedOs || !supportedArch) {
            throw new IOException("Interactive mode supports Ghostty on macOS and Linux (x86_64 or aarch64); "
                    + os + " " + arch + " is not supported. Use --print or --mode json|rpc instead.");
        }
        Libc libc;
        try {
            libc = Libc.get();
        } catch (RuntimeException | LinkageError error) {
            throw new IOException("Interactive mode cannot access the terminal: " + error.getMessage(), error);
        }
        if (!libc.isatty(0) || !libc.isatty(1)) {
            throw new IOException("Interactive mode requires a terminal on standard input and output. "
                    + "Use --print or --mode json|rpc for redirected input or output.");
        }
        return open(0, new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out), System.getenv("TERM"));
    }

    /** Opens a terminal whose line discipline and size are those of {@code fd}. */
    static PosixTerminal open(int fd, InputStream in, OutputStream out, String type) throws IOException {
        byte[] state = Libc.get().getAttributes(fd);
        PosixTerminal terminal = new PosixTerminal(type, fd, in, out, new Mode(isRaw(state), state));
        terminal.installSignals();
        Runtime.getRuntime().addShutdownHook(terminal.shutdownHook);
        return terminal;
    }

    private static boolean isRaw(byte[] state) {
        return (flags(state, LFLAG) & (ICANON | ECHO | IEXTEN | ISIG)) == 0;
    }

    @Override
    protected Size querySize() {
        try {
            return Libc.get().windowSize(fd);
        } catch (IOException error) {
            return new Size(0, 0);
        }
    }

    @Override
    protected Mode rawVariant(Mode cooked) {
        byte[] raw = cooked.state.clone();
        setFlags(raw, LFLAG, flags(raw, LFLAG) & ~(ICANON | ECHO | IEXTEN | ISIG));
        setFlags(raw, IFLAG, flags(raw, IFLAG) & ~(IXON | ICRNL | INLCR));
        raw[CONTROL_CHARS + VMIN] = 1;
        raw[CONTROL_CHARS + VTIME] = 0;
        return new Mode(true, raw);
    }

    @Override
    protected void applyMode(Mode next) {
        try {
            Libc.get().setAttributes(fd, next.state);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    // Current termios flags and the bits raw mode clears, for tests.

    long localFlags() throws IOException {
        return flags(Libc.get().getAttributes(fd), LFLAG);
    }

    long inputFlags() throws IOException {
        return flags(Libc.get().getAttributes(fd), IFLAG);
    }

    long outputFlags() throws IOException {
        return flags(Libc.get().getAttributes(fd), OFLAG);
    }

    static long rawLocalFlags() {
        return ICANON | ECHO | IEXTEN | ISIG;
    }

    static long rawInputFlags() {
        return IXON | ICRNL | INLCR;
    }

    private void installSignals() {
        for (Signal signal : Signal.values()) {
            try {
                sun.misc.SignalHandler previous = sun.misc.Signal.handle(new sun.misc.Signal(signal.name()), ignored -> {
                    if (signal == Signal.CONT) {
                        // A job-control shell may have reset the line discipline while this process was stopped.
                        try {
                            reapplyMode();
                        } catch (RuntimeException ignoredError) {
                            // Keep delivering the notification.
                        }
                    }
                    raise(signal);
                });
                previousHandlers.put(signal, previous);
            } catch (IllegalArgumentException unavailable) {
                // The VM reserves this signal; resize and resume then rely on polling.
            }
        }
    }

    private void restoreForExit() {
        if (isClosed()) return;
        try {
            write(restoreSequence());
        } catch (RuntimeException ignored) {
            // Best effort during shutdown.
        }
        try {
            Libc.get().setAttributes(fd, initialMode().state);
        } catch (IOException | RuntimeException ignored) {
            // Best effort during shutdown.
        }
    }

    @Override
    public void close() throws IOException {
        if (isClosed()) return;
        try {
            super.close();
        } finally {
            previousHandlers.forEach((signal, previous) -> {
                try {
                    sun.misc.Signal.handle(new sun.misc.Signal(signal.name()), previous);
                } catch (IllegalArgumentException ignored) {
                    // Nothing was installed.
                }
            });
            previousHandlers.clear();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shuttingDown) {
                // The hook is already running or has run.
            }
        }
    }

    private static long flags(byte[] state, long offset) {
        MemorySegment segment = MemorySegment.ofArray(state);
        return FLAG_BYTES == 8
                ? segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)
                : Integer.toUnsignedLong(segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset));
    }

    private static void setFlags(byte[] state, long offset, long value) {
        MemorySegment segment = MemorySegment.ofArray(state);
        if (FLAG_BYTES == 8) segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value);
        else segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, (int) value);
    }

    /** libc downcalls. */
    static final class Libc {
        private static volatile Libc instance;

        private final MethodHandle isatty;
        private final MethodHandle tcgetattr;
        private final MethodHandle tcsetattr;
        private final MethodHandle ioctl;

        private Libc() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();
            isatty = linker.downcallHandle(find(lookup, "isatty"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            tcgetattr = linker.downcallHandle(find(lookup, "tcgetattr"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            tcsetattr = linker.downcallHandle(find(lookup, "tcsetattr"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            ioctl = linker.downcallHandle(find(lookup, "ioctl"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                    Linker.Option.firstVariadicArg(2));
        }

        static Libc get() {
            Libc current = instance;
            if (current == null) {
                synchronized (Libc.class) {
                    if (instance == null) instance = new Libc();
                    current = instance;
                }
            }
            return current;
        }

        private static MemorySegment find(SymbolLookup lookup, String name) {
            return lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libc symbol not found: " + name));
        }

        boolean isatty(int fd) {
            try {
                return (int) isatty.invokeExact(fd) == 1;
            } catch (Throwable error) {
                throw rethrow(error);
            }
        }

        byte[] getAttributes(int fd) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment termios = arena.allocate(TERMIOS_BYTES, 8);
                int result = (int) tcgetattr.invokeExact(fd, termios);
                if (result != 0) throw new IOException("Cannot read terminal attributes");
                return termios.toArray(ValueLayout.JAVA_BYTE);
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw rethrow(error);
            }
        }

        void setAttributes(int fd, byte[] state) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment termios = arena.allocate(TERMIOS_BYTES, 8);
                MemorySegment.copy(state, 0, termios, ValueLayout.JAVA_BYTE, 0, TERMIOS_BYTES);
                int result = (int) tcsetattr.invokeExact(fd, TCSANOW, termios);
                if (result != 0) throw new IOException("Cannot set terminal attributes");
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw rethrow(error);
            }
        }

        Size windowSize(int fd) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment winsize = arena.allocate(8, 2);
                int result = (int) ioctl.invokeExact(fd, TIOCGWINSZ, winsize);
                if (result != 0) throw new IOException("Cannot read the terminal size");
                int rows = Short.toUnsignedInt(winsize.get(ValueLayout.JAVA_SHORT, 0));
                int columns = Short.toUnsignedInt(winsize.get(ValueLayout.JAVA_SHORT, 2));
                return new Size(columns, rows);
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw rethrow(error);
            }
        }

        private static RuntimeException rethrow(Throwable error) {
            if (error instanceof RuntimeException runtime) return runtime;
            if (error instanceof Error fatal) throw fatal;
            return new IllegalStateException(error);
        }
    }
}
