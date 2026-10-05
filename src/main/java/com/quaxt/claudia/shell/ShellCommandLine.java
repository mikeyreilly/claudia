package com.quaxt.claudia.shell;

import com.quaxt.claudia.cli.tools.BuiltInTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Platform-specific script transport and setup; stdin remains reserved for session input. */
public final class ShellCommandLine {
    private ShellCommandLine() {}

    /** Model-facing interpreter name, with a cached version from the executable we actually launch. */
    public static String displayName(BuiltInTools.Shell shell) {
        return shell == BuiltInTools.Shell.POWERSHELL ? PowerShellName.VALUE : shell.displayName;
    }

    private static final class PowerShellName {
        private static final String VALUE = detectPowerShellName();
    }

    private static String detectPowerShellName() {
        Process process = null;
        try {
            process = new ProcessBuilder(arguments(BuiltInTools.Shell.POWERSHELL,
                    "[Console]::Write($PSVersionTable.PSVersion.ToString())"))
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            try (var output = process.getInputStream()) {
                if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return powerShellDisplayName(new String(output.readNBytes(256), StandardCharsets.UTF_8).strip());
                }
            }
        } catch (IOException ignored) {
            // A missing or unavailable shell must not prevent registering the other tools.
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return powerShellDisplayName(null);
    }

    static String powerShellDisplayName(String version) {
        return version != null && version.matches("[0-9]+(?:\\.[0-9]+){1,3}")
                ? "PowerShell " + version + " (powershell.exe)"
                : "PowerShell (powershell.exe; version unavailable)";
    }

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
