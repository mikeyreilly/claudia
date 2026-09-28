package com.quaxt.codingagent.terminal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Exercises termios, window-size, and signal handling against a real pseudo-terminal. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class PosixTerminalTest {
    private static final boolean MAC = OS.current() == OS.MAC;
    private static final int O_RDWR = 2;
    private static final int O_NOCTTY = MAC ? 0x20000 : 0x100;
    private static final long TIOCSWINSZ = MAC ? 0x80087467L : 0x5414L;
    /** c_lflag offset and PENDIN, which the kernel sets itself when canonical mode resumes. */
    private static final int LFLAG_OFFSET = MAC ? 24 : 12;
    private static final long PENDIN = MAC ? 0x20000000L : 0x4000L;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final MethodHandle POSIX_OPENPT = downcall("posix_openpt",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle GRANTPT = downcall("grantpt",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle UNLOCKPT = downcall("unlockpt",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle PTSNAME = downcall("ptsname",
            FunctionDescriptor.of(ValueLayout.ADDRESS.withTargetLayout(
                    java.lang.foreign.MemoryLayout.sequenceLayout(1024, ValueLayout.JAVA_BYTE)), ValueLayout.JAVA_INT));
    private static final MethodHandle OPEN = downcall("open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE = downcall("close",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle IOCTL = LINKER.downcallHandle(LIBC.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            Linker.Option.firstVariadicArg(2));

    private int master = -1;
    private int slave = -1;
    private PosixTerminal terminal;

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), descriptor);
    }

    @BeforeEach
    void openPseudoTerminal() throws Throwable {
        master = (int) POSIX_OPENPT.invokeExact(O_RDWR | O_NOCTTY);
        assertTrue(master >= 0, "posix_openpt failed");
        assertEquals(0, (int) GRANTPT.invokeExact(master));
        assertEquals(0, (int) UNLOCKPT.invokeExact(master));
        String name = ((MemorySegment) PTSNAME.invokeExact(master)).getString(0);
        try (Arena arena = Arena.ofConfined()) {
            slave = (int) OPEN.invokeExact(arena.allocateFrom(name), O_RDWR | O_NOCTTY);
        }
        assertTrue(slave >= 0, "Could not open " + name);
        PipedInputStream input = new PipedInputStream();
        new PipedOutputStream(input);
        terminal = PosixTerminal.open(slave, input, new ByteArrayOutputStream(), "xterm-ghostty");
    }

    @AfterEach
    void closePseudoTerminal() throws Throwable {
        if (terminal != null) terminal.close();
        if (slave >= 0) {
            int ignored = (int) CLOSE.invokeExact(slave);
        }
        if (master >= 0) {
            int ignored = (int) CLOSE.invokeExact(master);
        }
    }

    private byte[] attributes() throws IOException {
        return PosixTerminal.Libc.get().getAttributes(slave);
    }

    /** The attributes without kernel-maintained state, for comparison. */
    private byte[] comparableAttributes() throws IOException {
        byte[] state = attributes();
        MemorySegment segment = MemorySegment.ofArray(state);
        if (MAC) {
            long flags = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, LFLAG_OFFSET);
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED, LFLAG_OFFSET, flags & ~PENDIN);
        } else {
            int flags = segment.get(ValueLayout.JAVA_INT_UNALIGNED, LFLAG_OFFSET);
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, LFLAG_OFFSET, (int) (flags & ~PENDIN));
        }
        return state;
    }

    private void setWindowSize(int rows, int columns) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment winsize = arena.allocate(8, 2);
            winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
            winsize.set(ValueLayout.JAVA_SHORT, 2, (short) columns);
            assertEquals(0, (int) IOCTL.invokeExact(master, TIOCSWINSZ, winsize));
        }
    }

    @Test
    void rawModeClearsOnlyLineDisciplineFlagsAndRestoresTheExactState() throws Exception {
        byte[] original = comparableAttributes();
        long outputFlags = terminal.outputFlags();
        assertFalse(terminal.mode().raw());
        assertEquals(PosixTerminal.rawLocalFlags(), terminal.localFlags() & PosixTerminal.rawLocalFlags(),
                "A new pseudo-terminal starts in canonical mode");

        Terminal.Mode previous = terminal.enterRawMode();
        assertTrue(terminal.mode().raw());
        assertEquals(0, terminal.localFlags() & PosixTerminal.rawLocalFlags());
        assertEquals(0, terminal.inputFlags() & PosixTerminal.rawInputFlags());
        assertEquals(outputFlags, terminal.outputFlags(), "Output processing keeps translating newlines");

        terminal.setMode(previous);
        assertArrayEquals(original, comparableAttributes());

        terminal.enterRawMode();
        terminal.close();
        assertArrayEquals(original, comparableAttributes(), "Closing restores the mode the terminal opened with");
    }

    @Test
    void reportsTheWindowSizeAndFallsBackWhenItIsUnset() throws Throwable {
        setWindowSize(33, 101);
        assertEquals(new Terminal.Size(101, 33), terminal.size());
        setWindowSize(0, 0);
        assertEquals(new Terminal.Size(Terminal.DEFAULT_COLUMNS, Terminal.DEFAULT_ROWS), terminal.size());
    }

    @Test
    void continueReappliesTheModeAShellChangedAndNotifiesHandlers() throws Exception {
        byte[] cooked = attributes();
        terminal.enterRawMode();
        CountDownLatch continued = new CountDownLatch(1);
        CountDownLatch resized = new CountDownLatch(1);
        terminal.handle(Terminal.Signal.CONT, continued::countDown);
        terminal.handle(Terminal.Signal.WINCH, resized::countDown);
        // A job-control shell restores its own mode while this process is stopped.
        PosixTerminal.Libc.get().setAttributes(slave, cooked);

        signal("CONT");
        assertTrue(continued.await(5, TimeUnit.SECONDS));
        assertEquals(0, terminal.localFlags() & PosixTerminal.rawLocalFlags());

        signal("WINCH");
        assertTrue(resized.await(5, TimeUnit.SECONDS));
    }

    private static void signal(String name) throws Exception {
        Process kill = new ProcessBuilder("/bin/kill", "-" + name, Long.toString(ProcessHandle.current().pid()))
                .redirectErrorStream(true)
                .start();
        assertEquals(0, kill.waitFor());
    }

    @Test
    void theSystemTerminalRequiresATerminalOnStandardStreams() {
        PosixTerminal.Libc libc = PosixTerminal.Libc.get();
        assumeFalse(libc.isatty(0) && libc.isatty(1), "Standard streams are a terminal");
        IOException error = assertThrows(IOException.class, Terminal::system);
        assertTrue(error.getMessage().contains("requires a terminal"), error.getMessage());
        assertTrue(error.getMessage().contains("--print"), error.getMessage());
    }
}
