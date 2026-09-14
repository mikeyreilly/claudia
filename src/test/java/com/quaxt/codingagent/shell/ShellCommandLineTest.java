package com.quaxt.codingagent.shell;

import com.quaxt.codingagent.cli.tools.BuiltInTools;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellCommandLineTest {
    @Test
    void advertisesPowerShellVersionAndExecutable() {
        assertEquals("PowerShell 5.1.26100.9444 (powershell.exe)",
                ShellCommandLine.powerShellDisplayName("5.1.26100.9444"));
        assertEquals("PowerShell 5.1 (powershell.exe)", ShellCommandLine.powerShellDisplayName("5.1"));
        assertEquals("bash", ShellCommandLine.displayName(BuiltInTools.Shell.BASH));
    }

    @Test
    void unavailableVersionsDoNotAdvertiseGuessedCapabilitiesOrProbeErrors() {
        String fallback = "PowerShell (powershell.exe; version unavailable)";
        assertEquals(fallback, ShellCommandLine.powerShellDisplayName(null));
        assertEquals(fallback, ShellCommandLine.powerShellDisplayName(""));
        assertEquals(fallback, ShellCommandLine.powerShellDisplayName("access denied"));
        assertEquals(fallback, ShellCommandLine.powerShellDisplayName("5.1\nUnexpected output"));
    }

    @Test
    void bashPassesTheScriptAsOneUnmodifiedArgument() {
        String command = "printf '%s\\n' \"$HOME\" 'café 雪 🚀'\n# keep this newline\necho done";

        for (boolean legacy : List.of(false, true)) {
            assertEquals(List.of("/bin/bash", "-c", command),
                    ShellCommandLine.arguments(BuiltInTools.Shell.BASH, command, legacy));
        }
    }

    @Test
    void powerShellUsesNativeEscapingOnlyInLegacyJdkMode() {
        String command = "$key = 'café 雪 🚀'\r\n\"Added model: $key\"\r\n# 'quotes' `backticks` & |\r\n";
        List<String> strict = ShellCommandLine.arguments(BuiltInTools.Shell.POWERSHELL, command, false);
        List<String> legacy = ShellCommandLine.arguments(BuiltInTools.Shell.POWERSHELL, command, true);

        List<String> prefix = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command");
        assertEquals(prefix, strict.subList(0, strict.size() - 1));
        assertEquals(prefix, legacy.subList(0, legacy.size() - 1));
        String script = strict.getLast();
        assertTrue(script.endsWith("\n" + command), script);
        assertTrue(script.contains("function global:Read-Host"));
        assertTrue(script.contains("[Console]::ReadLine()"));
        assertEquals(ShellCommandLine.quoteWindowsArgument(script), legacy.getLast());
    }

    @Test
    void quotesSpacesEmptyArgumentsAndEmbeddedDoubleQuotes() {
        assertEquals("\"\"", ShellCommandLine.quoteWindowsArgument(""));
        assertEquals("\"plain\"", ShellCommandLine.quoteWindowsArgument("plain"));
        assertEquals("\"two words\"", ShellCommandLine.quoteWindowsArgument("two words"));
        assertEquals("\"\\\"quoted\\\"\"", ShellCommandLine.quoteWindowsArgument("\"quoted\""));
        assertEquals("\"'single' $value & |\"", ShellCommandLine.quoteWindowsArgument("'single' $value & |"));
    }

    @Test
    void doublesBackslashesOnlyBeforeQuotesAndAtTheEnd() {
        assertEquals("\"C:\\path with spaces\\\\\"",
                ShellCommandLine.quoteWindowsArgument("C:\\path with spaces\\"));
        for (int count = 0; count <= 4; count++) {
            String slashes = "\\".repeat(count);
            assertEquals("\"before" + "\\".repeat(2 * count + 1) + "\"after\"",
                    ShellCommandLine.quoteWindowsArgument("before" + slashes + "\"after"));
            assertEquals("\"before" + "\\".repeat(2 * count) + "\"",
                    ShellCommandLine.quoteWindowsArgument("before" + slashes));
            assertEquals("\"before" + slashes + "after\"",
                    ShellCommandLine.quoteWindowsArgument("before" + slashes + "after"));
        }
    }
}
