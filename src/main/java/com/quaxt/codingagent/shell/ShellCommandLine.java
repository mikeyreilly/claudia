package com.quaxt.codingagent.shell;

import com.quaxt.codingagent.cli.tools.BuiltInTools;
import java.util.List;

/** Platform-specific script transport and setup; stdin remains reserved for session input. */
final class ShellCommandLine {
    private ShellCommandLine() {}

    // PowerShell's console host bypasses redirected stdout for Read-Host prompts.
    // Adapt that cmdlet to our pipes; other console-only prompts still fail promptly.
    private static final String POWERSHELL_STDIN_PREAMBLE = """
            $OutputEncoding = [Console]::InputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false);
            function global:Read-Host {
                [CmdletBinding()]
                param([Parameter(Position=0)][object]$Prompt, [switch]$AsSecureString, [switch]$MaskInput)
                if ($null -ne $Prompt) { [Console]::Write([string]$Prompt + ': '); [Console]::Out.Flush() }
                $reply = [Console]::ReadLine()
                if ($null -eq $reply) { throw 'Command stdin closed while waiting for input' }
                if ($AsSecureString) { ConvertTo-SecureString -String $reply -AsPlainText -Force } else { $reply }
            }
            """;

    static List<String> arguments(BuiltInTools.Shell shell, String command) {
        boolean legacyWindowsQuoting = !"false".equalsIgnoreCase(
                System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true"));
        return arguments(shell, command, legacyWindowsQuoting);
    }

    static List<String> arguments(BuiltInTools.Shell shell, String command, boolean legacyWindowsQuoting) {
        return switch (shell) {
            case BASH -> List.of("/bin/bash", "-c", command);
            case POWERSHELL -> {
                String script = POWERSHELL_STDIN_PREAMBLE + command;
                // The JDK's default (legacy) Windows mode adds outer quotes but does not
                // escape embedded quotes. Supply a fully quoted native argument in that
                // mode; the strict mode already performs this escaping itself. Do not
                // change the JVM-wide property, which also controls unrelated processes.
                // Keep -Command: -EncodedCommand makes Windows PowerShell serialize stderr
                // as CLIXML, and Base64 would reduce the maximum supported script length.
                yield List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        legacyWindowsQuoting ? quoteWindowsArgument(script) : script);
            }
        };
    }

    /** Quotes one argument for Windows' native argv parser, not for PowerShell syntax. */
    static String quoteWindowsArgument(String argument) {
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else {
                // Backslashes are literal except immediately before a double quote.
                quoted.append("\\".repeat(c == '"' ? 2 * backslashes + 1 : backslashes));
                quoted.append(c);
                backslashes = 0;
            }
        }
        // Double trailing backslashes so they cannot escape the closing quote.
        return quoted.append("\\".repeat(2 * backslashes)).append('"').toString();
    }
}
