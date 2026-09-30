package com.quaxt.codingagent.terminal;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** The Windows console, using its virtual-terminal input and output modes. */
final class WindowsTerminal extends Terminal {
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int ENABLE_PROCESSED_INPUT = 0x0001;
    private static final int ENABLE_LINE_INPUT = 0x0002;
    private static final int ENABLE_ECHO_INPUT = 0x0004;
    private static final int ENABLE_WINDOW_INPUT = 0x0008;
    private static final int ENABLE_MOUSE_INPUT = 0x0010;
    private static final int ENABLE_QUICK_EDIT_MODE = 0x0040;
    private static final int ENABLE_EXTENDED_FLAGS = 0x0080;
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;
    private static final int ENABLE_PROCESSED_OUTPUT = 0x0001;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;
    private static final int UTF8 = 65001;

    private final Win32 win32;
    private final MemorySegment inputHandle;
    private final MemorySegment outputHandle;
    private final int outputMode;
    private final int inputCodePage;
    private final int outputCodePage;
    private final Thread shutdownHook;

    private WindowsTerminal(Win32 win32, MemorySegment inputHandle, MemorySegment outputHandle,
            int inputMode, int outputMode, int inputCodePage, int outputCodePage) {
        super("windows-vt", new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out),
                null, new Mode(isRaw(inputMode), state(inputMode)));
        this.win32 = win32;
        this.inputHandle = inputHandle;
        this.outputHandle = outputHandle;
        this.outputMode = outputMode;
        this.inputCodePage = inputCodePage;
        this.outputCodePage = outputCodePage;
        this.shutdownHook = new Thread(this::restoreForExit, "codingagent-terminal-restore");
    }

    static WindowsTerminal open() throws IOException {
        Win32 win32;
        try {
            win32 = new Win32();
        } catch (RuntimeException | LinkageError error) {
            throw new IOException("Interactive mode cannot access the Windows console: " + error.getMessage(), error);
        }
        MemorySegment input = win32.stdHandle(STD_INPUT_HANDLE);
        MemorySegment output = win32.stdHandle(STD_OUTPUT_HANDLE);
        Integer inputMode = win32.consoleMode(input);
        Integer outputMode = win32.consoleMode(output);
        if (inputMode == null || outputMode == null) {
            throw new IOException("Interactive mode requires a console on standard input and output. "
                    + "Use --print or --mode json|rpc for redirected input or output.");
        }
        int inputCodePage = win32.consoleCodePage(false);
        int outputCodePage = win32.consoleCodePage(true);
        WindowsTerminal terminal = new WindowsTerminal(win32, input, output, inputMode, outputMode,
                inputCodePage, outputCodePage);
        try {
            win32.setConsoleMode(output, outputMode | ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
            win32.setConsoleCodePage(false, UTF8);
            win32.setConsoleCodePage(true, UTF8);
            Runtime.getRuntime().addShutdownHook(terminal.shutdownHook);
            return terminal;
        } catch (IOException | RuntimeException | Error failure) {
            terminal.restoreConsole();
            throw failure;
        }
    }

    private static boolean isRaw(int mode) {
        return (mode & (ENABLE_PROCESSED_INPUT | ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT)) == 0
                && (mode & ENABLE_VIRTUAL_TERMINAL_INPUT) != 0;
    }

    static int rawInputMode(int cooked) {
        return (cooked & ~(ENABLE_PROCESSED_INPUT | ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT
                | ENABLE_WINDOW_INPUT | ENABLE_MOUSE_INPUT | ENABLE_QUICK_EDIT_MODE))
                | ENABLE_EXTENDED_FLAGS | ENABLE_VIRTUAL_TERMINAL_INPUT;
    }

    private static byte[] state(int mode) {
        byte[] state = new byte[Integer.BYTES];
        MemorySegment.ofArray(state).set(ValueLayout.JAVA_INT_UNALIGNED, 0, mode);
        return state;
    }

    private static int inputMode(Mode mode) {
        return MemorySegment.ofArray(mode.state).get(ValueLayout.JAVA_INT_UNALIGNED, 0);
    }

    @Override
    protected Mode rawVariant(Mode cooked) {
        return new Mode(true, state(rawInputMode(inputMode(cooked))));
    }

    @Override
    protected void applyMode(Mode next) {
        try {
            win32.setConsoleMode(inputHandle, inputMode(next));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    protected Size querySize() {
        try {
            return win32.windowSize(outputHandle);
        } catch (IOException error) {
            return new Size(0, 0);
        }
    }

    private void restoreConsole() {
        try { win32.setConsoleMode(inputHandle, inputMode(initialMode())); }
        catch (IOException ignored) { /* The console may already be gone. */ }
        try { win32.setConsoleMode(outputHandle, outputMode); }
        catch (IOException ignored) { /* The console may already be gone. */ }
        try { win32.setConsoleCodePage(false, inputCodePage); }
        catch (IOException ignored) { /* The console may already be gone. */ }
        try { win32.setConsoleCodePage(true, outputCodePage); }
        catch (IOException ignored) { /* The console may already be gone. */ }
    }

    private void restoreForExit() {
        if (isClosed()) return;
        try { write(restoreSequence()); }
        catch (RuntimeException ignored) { /* Best effort during shutdown. */ }
        restoreConsole();
    }

    @Override
    public void close() throws IOException {
        if (isClosed()) return;
        try {
            super.close();
        } finally {
            restoreConsole();
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* Shutdown is already in progress. */ }
        }
    }

    /** Win32 console calls, loaded only when Windows interactive mode is requested. */
    private static final class Win32 {
        private final MethodHandle getStdHandle;
        private final MethodHandle getConsoleMode;
        private final MethodHandle setConsoleMode;
        private final MethodHandle getConsoleScreenBufferInfo;
        private final MethodHandle getConsoleCP;
        private final MethodHandle getConsoleOutputCP;
        private final MethodHandle setConsoleCP;
        private final MethodHandle setConsoleOutputCP;

        private Win32() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.libraryLookup("kernel32", Arena.global());
            getStdHandle = linker.downcallHandle(find(symbols, "GetStdHandle"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            getConsoleMode = linker.downcallHandle(find(symbols, "GetConsoleMode"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            setConsoleMode = linker.downcallHandle(find(symbols, "SetConsoleMode"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            getConsoleScreenBufferInfo = linker.downcallHandle(find(symbols, "GetConsoleScreenBufferInfo"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getConsoleCP = linker.downcallHandle(find(symbols, "GetConsoleCP"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            getConsoleOutputCP = linker.downcallHandle(find(symbols, "GetConsoleOutputCP"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            setConsoleCP = linker.downcallHandle(find(symbols, "SetConsoleCP"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            setConsoleOutputCP = linker.downcallHandle(find(symbols, "SetConsoleOutputCP"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        }

        private static MemorySegment find(SymbolLookup lookup, String name) {
            return lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Win32 symbol not found: " + name));
        }

        private MemorySegment stdHandle(int which) throws IOException {
            try {
                MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(which);
                if (handle.address() == 0 || handle.address() == -1L) {
                    throw new IOException("Cannot obtain standard console handle " + which);
                }
                return handle;
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("Cannot obtain standard console handle", error);
            }
        }

        private Integer consoleMode(MemorySegment handle) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
                return (int) getConsoleMode.invokeExact(handle, mode) == 0
                        ? null : mode.get(ValueLayout.JAVA_INT, 0);
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("Cannot read Windows console mode", error);
            }
        }

        private void setConsoleMode(MemorySegment handle, int mode) throws IOException {
            try {
                if ((int) setConsoleMode.invokeExact(handle, mode) == 0)
                    throw new IOException("Cannot set Windows console mode");
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("Cannot set Windows console mode", error);
            }
        }

        private int consoleCodePage(boolean output) throws IOException {
            try {
                int page = output ? (int) getConsoleOutputCP.invokeExact() : (int) getConsoleCP.invokeExact();
                if (page == 0) throw new IOException("Cannot read Windows console code page");
                return page;
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("Cannot read Windows console code page", error);
            }
        }

        private void setConsoleCodePage(boolean output, int page) throws IOException {
            try {
                int result = output ? (int) setConsoleOutputCP.invokeExact(page)
                        : (int) setConsoleCP.invokeExact(page);
                if (result == 0) throw new IOException("Cannot set Windows console code page");
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("Cannot set Windows console code page", error);
            }
        }

        private Size windowSize(MemorySegment handle) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                // CONSOLE_SCREEN_BUFFER_INFO: COORD, COORD, WORD, SMALL_RECT, COORD.
                MemorySegment info = arena.allocate(22, 2);
                if ((int) getConsoleScreenBufferInfo.invokeExact(handle, info) == 0)
                    throw new IOException("Cannot read Windows console size");
                int left = info.get(ValueLayout.JAVA_SHORT, 10);
                int top = info.get(ValueLayout.JAVA_SHORT, 12);
                int right = info.get(ValueLayout.JAVA_SHORT, 14);
                int bottom = info.get(ValueLayout.JAVA_SHORT, 16);
                return new Size(right - left + 1, bottom - top + 1);
            } catch (IOException | RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IOException("Cannot read Windows console size", error);
            }
        }
    }
}
