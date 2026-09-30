package com.quaxt.codingagent.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class WindowsTerminalTest {
    @Test
    void rawModeEnablesVtInputAndDisablesCookedInputAndQuickEdit() {
        int cooked = 0x01ff;
        int raw = WindowsTerminal.rawInputMode(cooked);
        assertEquals(0, raw & (0x0001 | 0x0002 | 0x0004 | 0x0008 | 0x0010 | 0x0040));
        assertEquals(0x0080 | 0x0200, raw & (0x0080 | 0x0200));
        assertEquals(cooked & 0x0020, raw & 0x0020);
    }

    @Test
    void systemConsoleEitherOpensOrExplainsRedirectedStreams() throws Exception {
        try (Terminal terminal = Terminal.system()) {
            assertInstanceOf(WindowsTerminal.class, terminal);
            assertTrue(terminal.size().columns() > 0);
            Terminal.Mode original = terminal.enterRawMode();
            assertTrue(terminal.mode().raw());
            terminal.setMode(original);
        } catch (IOException error) {
            assertTrue(error.getMessage().contains("requires a console"), error.getMessage());
            assertTrue(error.getMessage().contains("--print"), error.getMessage());
        }
    }
}
