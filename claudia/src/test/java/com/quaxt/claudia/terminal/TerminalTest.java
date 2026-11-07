package com.quaxt.claudia.terminal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TerminalTest {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Terminal terminal = Terminal.streams(
            "xterm-ghostty", new ByteArrayInputStream(new byte[0]), output, 20, 6);

    private static int count(String value, String target) {
        int count = 0;
        for (int offset = value.indexOf(target); offset >= 0; offset = value.indexOf(target, offset + target.length())) {
            count++;
        }
        return count;
    }

    @Test
    void rawModeReturnsThePreviousModeForRestoration() {
        Terminal.Mode cooked = terminal.mode();
        assertFalse(cooked.raw());
        assertSame(cooked, terminal.enterRawMode());
        Terminal.Mode raw = terminal.mode();
        assertTrue(raw.raw());
        assertSame(raw, terminal.enterRawMode(), "Entering raw mode again changes nothing");
        terminal.setMode(cooked);
        assertFalse(terminal.mode().raw());
    }

    /**
     * On resume the SIGCONT handler reapplies the mode while the main thread
     * restores raw mode. If the handler read the old cooked mode and applied it
     * after the main thread's raw mode reached the tty, the tty stayed cooked
     * with kitty key reporting on: Ctrl-C, Ctrl-D, and Ctrl-Z were echoed as
     * {@code ^[[99;5u} and friends instead of acting.
     */
    @Test
    void reapplyingTheModeOnResumeCannotUndoAConcurrentModeChange() throws Exception {
        Thread main = Thread.currentThread();
        AtomicReference<Terminal.Mode> tty = new AtomicReference<>();
        CountDownLatch rawApplied = new CountDownLatch(1);
        CountDownLatch reapplied = new CountDownLatch(1);
        Terminal racing = new Terminal("xterm-ghostty", new ByteArrayInputStream(new byte[0]), output,
                new Terminal.Size(20, 6), terminal.mode()) {
            @Override
            protected void applyMode(Mode next) {
                tty.set(next);
                if (next.raw() && Thread.currentThread() == main) {
                    // The line discipline is raw but the tracked mode is not yet updated.
                    rawApplied.countDown();
                    try {
                        reapplied.await(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        Thread resume = Thread.ofPlatform().start(() -> {
            try {
                rawApplied.await();
            } catch (InterruptedException interrupted) {
                return;
            }
            racing.reapplyMode();
            reapplied.countDown();
        });

        racing.enterRawMode();
        resume.join(5_000);

        assertFalse(resume.isAlive());
        assertTrue(racing.mode().raw());
        assertSame(racing.mode(), tty.get(), "The tty must end in the mode the terminal reports");
    }

    @Test
    void substitutesDefaultsForUnreportedDimensions() {
        terminal.setSize(0, 0);
        assertEquals(new Terminal.Size(80, 24), terminal.size());
        terminal.setSize(100, -1);
        assertEquals(new Terminal.Size(100, 24), terminal.size());
    }

    @Test
    void deliversSignalsUntilHandlersAreRemoved() throws Exception {
        List<String> seen = new ArrayList<>();
        AutoCloseable resize = terminal.handle(Terminal.Signal.WINCH, () -> seen.add("resize"));
        terminal.handle(Terminal.Signal.CONT, () -> seen.add("continue"));
        terminal.raise(Terminal.Signal.WINCH);
        terminal.raise(Terminal.Signal.CONT);
        resize.close();
        terminal.raise(Terminal.Signal.WINCH);
        assertEquals(List.of("resize", "continue"), seen);
    }

    @Test
    void nestedSynchronizedUpdatesAreWrittenOnce() {
        terminal.beginUpdate();
        terminal.beginUpdate();
        terminal.write("frame");
        terminal.endUpdate();
        terminal.endUpdate();
        terminal.endUpdate();
        assertEquals(Ansi.BEGIN_SYNCHRONIZED_UPDATE + "frame" + Ansi.END_SYNCHRONIZED_UPDATE, output.toString(UTF_8));
    }

    @Test
    void closeUndoesEveryActiveFeatureAndRestoresTheMode() throws Exception {
        terminal.popKeyboardFlags();
        assertEquals("", output.toString(UTF_8), "Nothing to pop");
        terminal.enterRawMode();
        terminal.pushKeyboardFlags(1);
        terminal.pushKeyboardFlags(1);
        terminal.alternateScreen(true);
        terminal.popKeyboardFlags();
        assertEquals(-1, output.toString(UTF_8).indexOf(Ansi.POP_KEYBOARD_FLAGS),
                "The alternate screen has its own, empty, flag stack");
        terminal.pushKeyboardFlags(1);
        terminal.mouseTracking(true);
        terminal.bracketedPaste(true);
        terminal.cursorVisible(false);
        terminal.scrollRegion(1, 5);
        terminal.beginUpdate();
        output.reset();

        terminal.close();

        String written = output.toString(UTF_8);
        assertEquals(3, count(written, Ansi.POP_KEYBOARD_FLAGS));
        for (String expected : List.of(Ansi.END_SYNCHRONIZED_UPDATE, Ansi.MOUSE_OFF, Ansi.BRACKETED_PASTE_OFF,
                Ansi.ALTERNATE_SCREEN_OFF, Ansi.RESET_SCROLL_REGION, Ansi.CURSOR_SHOW, Ansi.RESET)) {
            assertTrue(written.contains(expected), expected.replace("\u001b", "ESC"));
        }
        int leave = written.indexOf(Ansi.ALTERNATE_SCREEN_OFF);
        assertEquals(1, count(written.substring(0, leave), Ansi.POP_KEYBOARD_FLAGS),
                "Each screen's flags are popped on that screen");
        assertEquals(2, count(written.substring(leave), Ansi.POP_KEYBOARD_FLAGS));
        assertFalse(terminal.mode().raw());
        output.reset();
        terminal.close();
        assertEquals("", output.toString(UTF_8));
    }

    @Test
    void tracksMouseReportingAndReassertsItOnlyWhileEnabled() {
        assertFalse(terminal.mouseTracking());
        terminal.reassertMouseTracking();
        assertEquals("", output.toString(UTF_8));
        terminal.mouseTracking(true);
        assertTrue(terminal.mouseTracking());
        output.reset();
        terminal.reassertMouseTracking();
        assertEquals(Ansi.MOUSE_ON, output.toString(UTF_8));
        terminal.mouseTracking(false);
        assertFalse(terminal.mouseTracking());
    }

    @Test
    void win32InputIsIdempotentAndRestoredOnClose() throws Exception {
        terminal.win32Input(true);
        terminal.win32Input(true);
        assertEquals(Ansi.WIN32_INPUT_ON, output.toString(UTF_8));
        output.reset();
        terminal.close();
        assertEquals(Ansi.WIN32_INPUT_OFF + Ansi.RESET, output.toString(UTF_8));
    }

    @Test
    void closeAfterFeaturesWereTurnedOffWritesNothing() throws Exception {
        terminal.win32Input(true);
        terminal.win32Input(false);
        terminal.pushKeyboardFlags(1);
        terminal.popKeyboardFlags();
        terminal.alternateScreen(true);
        terminal.alternateScreen(false);
        terminal.mouseTracking(true);
        terminal.mouseTracking(false);
        output.reset();
        terminal.close();
        assertEquals("", output.toString(UTF_8));
    }

    @Test
    void statusLineKeepsTheBottomRowWhileOutputScrolls() {
        StatusLine status = new StatusLine(terminal);
        terminal.write("one\r\ntwo\r\n");
        status.update("status");
        assertTrue(status.isShown());
        ScreenEmulator screen = ScreenEmulator.render(output.toString(UTF_8), 20, 6);
        assertEquals("status", screen.line(5));
        assertEquals(2, screen.cursorRow());
        for (int index = 0; index < 8; index++) terminal.write("line " + index + "\r\n");
        status.update("updated");
        screen = ScreenEmulator.render(output.toString(UTF_8), 20, 6);
        assertEquals(List.of("line 4", "line 5", "line 6", "line 7", "", "updated"), screen.lines());
        assertEquals(4, screen.cursorRow());
    }

    @Test
    void statusLineReleasesTheRowWhenSuspendedAndReservesItAgain() {
        StatusLine status = new StatusLine(terminal);
        status.update("status");
        status.suspend();
        assertFalse(status.isShown());
        status.update("latest");
        ScreenEmulator screen = ScreenEmulator.render(output.toString(UTF_8), 20, 6);
        assertEquals("", screen.line(5), "A suspended status is not drawn");
        // With the full screen available, output reaches the bottom row.
        for (int index = 0; index < 6; index++) terminal.write("\r\nrow " + index);
        screen = ScreenEmulator.render(output.toString(UTF_8), 20, 6);
        assertEquals("row 5", screen.line(5));
        assertEquals(5, screen.cursorRow());

        status.restore();
        screen = ScreenEmulator.render(output.toString(UTF_8), 20, 6);
        assertEquals("latest", screen.line(5));
        assertEquals("row 5", screen.line(4), "The cursor's row scrolls up out of the status row");
        assertEquals(4, screen.cursorRow());

        status.close();
        screen = ScreenEmulator.render(output.toString(UTF_8), 20, 6);
        assertEquals("", screen.line(5));
        assertFalse(status.isShown());
    }
}
