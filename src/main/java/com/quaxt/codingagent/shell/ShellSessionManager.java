package com.quaxt.codingagent.shell;

import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns the processes, I/O, deadlines, and cleanup for one agent's shell sessions. */
public final class ShellSessionManager implements AutoCloseable {
    public static final int MAX_INPUT_CHARACTERS = BuiltInTools.MAX_BYTES;
    private final Map<String, ShellSession> shellSessions = new ConcurrentHashMap<>();
    private Thread shutdownHook;
    private boolean closed;

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

    /** Starts a process and returns its current output, retaining it if still running. */
    public AgentTool.ToolResult execute(Path cwd, BuiltInTools.Shell shell, String command,
            Double timeoutSeconds, int yieldMs, BooleanSupplier cancelled) throws IOException, InterruptedException {
        validateYield(yieldMs);
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command must not be blank");
        if (timeoutSeconds != null && (timeoutSeconds <= 0 || !Double.isFinite(timeoutSeconds))) {
            throw new IllegalArgumentException("timeout must be a positive finite number");
        }
        ShellSession session = startShellSession(cwd, shell, command, timeoutSeconds == null ? 0 : timeoutSeconds, cancelled);
        return pollShellSession(session, yieldMs, cancelled);
    }

    /** Polls, writes literal stdin, sends EOF, or terminates an existing process. */
    public AgentTool.ToolResult interact(String id, String input, boolean closeStdin, boolean terminate,
            int yieldMs, BooleanSupplier cancelled) throws InterruptedException {
        validateYield(yieldMs);
        if (terminate && (input != null || closeStdin)) {
            throw new IllegalArgumentException("terminate cannot be combined with input or close_stdin");
        }
        requireNotCancelled(cancelled);
        ShellSession session = shellSessions.get(id);
        if (session == null) throw new IllegalArgumentException(
                "Unknown shell session: " + id + ". Shell sessions do not survive a reset or restart.");
        if (terminate) stopShellSession(session, "Command terminated");
        else if (input != null || closeStdin) sendShellInput(session, input == null ? "" : input, closeStdin);
        return pollShellSession(session, yieldMs, cancelled);
    }

    private static void validateYield(int yieldMs) {
        if (yieldMs < 0 || yieldMs > 30_000) throw new IllegalArgumentException("yield_ms must be an integer between 0 and 30000");
    }

    private static void requireNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Command aborted");
    }

    /** Permanently closes this manager and releases its JVM shutdown hook. */
    @Override
    public void close() {
        synchronized (shellSessions) {
            closed = true;
            closeSessions();
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running the hook.
                }
                shutdownHook = null;
            }
        }
    }

    private ShellSession startShellSession(
            Path cwd, BuiltInTools.Shell shell, String command, double timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        ShellSession session;
        synchronized (shellSessions) {
            requireNotCancelled(cancelled);
            if (closed) throw new IllegalStateException("Shell session manager is closed");
            if (shellSessions.size() >= 32) {
                throw new IllegalStateException("Too many shell sessions; poll or terminate existing sessions with shell_input first");
            }
            if (shutdownHook == null) {
                shutdownHook = new Thread(this::closeSessions, "codingagent-shell-cleanup");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }
            ProcessBuilder builder = new ProcessBuilder(switch (shell) {
                case BASH -> List.of("/bin/bash", "-c", command);
                case POWERSHELL -> List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        POWERSHELL_STDIN_PREAMBLE + command);
            }).directory(cwd.toFile()).redirectErrorStream(true);
            // Python otherwise buffers stdout when attached to a pipe, hiding input prompts.
            builder.environment().putIfAbsent("PYTHONUNBUFFERED", "1");
            session = new ShellSession(java.util.UUID.randomUUID().toString(), builder.start());
            shellSessions.put(session.id, session);
        }
        Thread.ofVirtual().name("codingagent-shell-output").start(() -> {
            try (var reader = new InputStreamReader(session.process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4_096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    synchronized (session) {
                        session.output.append(buffer, 0, count);
                        int excess = session.output.length() - BuiltInTools.MAX_BYTES;
                        if (excess > 0) {
                            if (Character.isLowSurrogate(session.output.charAt(excess))) excess++;
                            session.output.delete(0, excess);
                            session.truncated = true;
                        }
                    }
                }
            } catch (IOException error) {
                if (!session.stopped) stopShellSession(session, "Could not read command output: " + error.getMessage());
            } finally {
                session.outputComplete = true;
            }
        });
        if (timeoutSeconds > 0) {
            long started = System.nanoTime();
            Thread.ofVirtual().name("codingagent-shell-timeout").start(() -> {
                try {
                    while (!session.stopped && (session.process.isAlive() || !session.outputComplete)) {
                        if (System.nanoTime() - started >= timeoutSeconds * 1_000_000_000d) {
                            stopShellSession(session, "Command timed out after " + timeoutSeconds + " seconds");
                            break;
                        }
                        Thread.sleep(50);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    stopShellSession(session, "Command aborted");
                }
            });
        }
        return session;
    }

    private void sendShellInput(ShellSession session, String input, boolean closeStdin) {
        if (input.codePointCount(0, input.length()) > MAX_INPUT_CHARACTERS) throw new IllegalArgumentException("input is limited to " + MAX_INPUT_CHARACTERS + " characters per call");
        synchronized (session) {
            // A process may finish while the user is answering. Return its final output instead of losing it.
            if (session.stopped || !session.process.isAlive()) return;
            if (session.inputPending) throw new IllegalStateException("Previous input is still being written; poll before sending more input");
            if (session.stdinClosed) throw new IllegalStateException("Command stdin is already closed");
            session.inputPending = true;
            session.stdinClosed = closeStdin;
        }
        // Writing to a full pipe must not block the tool's polling/cancellation loop.
        Thread.ofVirtual().name("codingagent-shell-input").start(() -> {
            try {
                var stdin = session.process.getOutputStream();
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                if (closeStdin) stdin.close();
            } catch (IOException error) {
                stopShellSession(session, "Could not write command input: " + error.getMessage());
            } finally {
                session.inputPending = false;
            }
        });
    }

    private AgentTool.ToolResult pollShellSession(ShellSession session, int yieldMs, BooleanSupplier cancelled)
            throws InterruptedException {
        long started = System.nanoTime();
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    stopShellSession(session, "Command aborted");
                    break;
                }
                if (shellSessionFinished(session)) break;
                if (System.nanoTime() - started >= yieldMs * 1_000_000L) break;
                Thread.sleep(25);
            }
        } catch (InterruptedException error) {
            stopShellSession(session, "Command aborted");
            shellSessions.remove(session.id, session);
            throw error;
        }
        boolean finished = shellSessionFinished(session);
        String output;
        synchronized (session) {
            output = boundShellOutput(session.output.toString(), session.truncated);
            session.output.setLength(0);
            session.truncated = false;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("session_id", session.id);
        details.put("status", finished ? "exited" : session.stopped ? "stopping" : "running");
        details.put("input_pending", session.inputPending);
        String failure = session.failure;
        if (finished) {
            shellSessions.remove(session.id, session);
            if (!session.process.isAlive()) {
                int code = session.process.exitValue();
                details.put("exit_code", code);
                if (failure == null && code != 0) failure = "Command exited with code " + code;
            }
            if (failure != null) output += (output.isBlank() ? "" : "\n\n") + failure;
        } else if (session.stopped) {
            output += (output.isBlank() ? "" : "\n\n") + "[Shell session " + session.id
                    + " is stopping: " + failure + ". Use shell_input to collect its final result.]";
        } else {
            output += (output.isBlank() ? "" : "\n\n") + "[Shell session " + session.id
                    + " is still running. Use shell_input to poll or send input. If the output requests user information,"
                    + " ask the user and end this turn; after their reply, send it to this session, including a newline to submit it.]";
        }
        return new AgentTool.ToolResult(List.of(new TextContent(output.isBlank() ? "(no output)" : output, null)),
                details, finished && failure != null);
    }

    private void stopShellSession(ShellSession session, String reason) {
        synchronized (session) {
            if (session.stopped) return;
            session.failure = reason;
            session.stopped = true;
        }
        // Terminate children before their shell so ordinary scripts cannot remain orphaned.
        try {
            List<ProcessHandle> descendants = session.process.descendants().toList();
            for (ProcessHandle child : descendants.reversed()) child.destroyForcibly();
            session.process.destroyForcibly();
            awaitShellExit(session);
        } finally {
            session.terminationComplete = true;
        }
    }

    private static boolean shellSessionFinished(ShellSession session) {
        return session.stopped ? session.terminationComplete : !session.process.isAlive() && session.outputComplete;
    }

    private static void awaitShellExit(ShellSession session) {
        try {
            session.process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keep the tail so a prompt following a large log remains visible, including partial lines. */
    private static String boundShellOutput(String output, boolean truncated) {
        int start = output.length();
        int bytes = 0;
        int lines = 1;
        while (start > 0) {
            int codePoint = output.codePointBefore(start);
            int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + width > BuiltInTools.MAX_BYTES) break;
            if (codePoint == '\n' && start < output.length() && ++lines > BuiltInTools.MAX_LINES) break;
            bytes += width;
            start -= Character.charCount(codePoint);
        }
        return (truncated || start > 0 ? "[Earlier command output truncated; showing most recent output]\n" : "")
                + output.substring(start);
    }

    /** Releases live processes when cancelling, resetting a conversation, or closing the application. */
    public void closeSessions() {
        synchronized (shellSessions) {
            for (ShellSession session : shellSessions.values()) {
                stopShellSession(session, "Command aborted");
                awaitShellExit(session);
            }
            shellSessions.clear();
        }
    }

    /** A live shell and its unread output, retained across conversation turns. */
    private static final class ShellSession {
        public final String id;
        public final Process process;
        public final StringBuilder output = new StringBuilder();
        public boolean truncated;
        public volatile boolean outputComplete;
        public volatile String failure;
        public volatile boolean inputPending;
        public boolean stdinClosed;
        public volatile boolean stopped;
        public volatile boolean terminationComplete;

        public ShellSession(String id, Process process) {
            this.id = id;
            this.process = process;
        }
    }

}
