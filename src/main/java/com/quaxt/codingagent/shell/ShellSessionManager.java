package com.quaxt.codingagent.shell;

import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Owns the processes, I/O, deadlines, and cleanup for one agent's shell sessions. */
public final class ShellSessionManager implements AutoCloseable {
    public static final int MAX_INPUT_CHARACTERS = BuiltInTools.MAX_BYTES;
    private final Map<String, ShellSession> shellSessions = new ConcurrentHashMap<>();
    private Thread shutdownHook;
    private boolean closed;

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

    /** Runs an executable directly, preserving the caller's argument boundaries. */
    public AgentTool.ToolResult executeProcess(Path cwd, String executable, List<String> arguments,
            Map<String, String> environment, List<String> unsetEnvironment, boolean inheritEnvironment,
            Path stdoutLog, Path stderrLog, Double timeoutSeconds, int yieldMs, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        validateYield(yieldMs);
        if (timeoutSeconds != null && (timeoutSeconds <= 0 || !Double.isFinite(timeoutSeconds))) {
            throw new IllegalArgumentException("timeout must be a positive finite number");
        }
        for (String argument : arguments) ProcessEnvironment.validate(argument, "argument");
        if (stdoutLog != null && stdoutLog.equals(stderrLog)) {
            throw new IllegalArgumentException("stdout_log and stderr_log must be different files");
        }
        ProcessBuilder builder = new ProcessBuilder();
        builder.directory(cwd.toFile());
        ProcessEnvironment.apply(builder.environment(), environment, unsetEnvironment, inheritEnvironment);
        Path child = ProcessEnvironment.resolveExecutable(executable, cwd, builder.environment());
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(child.toString());
        for (String argument : arguments) command.add(ProcessEnvironment.processBuilderArgument(argument));
        builder.command(command);
        OutputStream stdoutWriter = openLog(stdoutLog);
        OutputStream stderrWriter;
        try {
            stderrWriter = openLog(stderrLog);
        } catch (IOException error) {
            if (stdoutWriter != null) stdoutWriter.close();
            throw error;
        }
        ShellSession session;
        try {
            synchronized (shellSessions) {
                requireNotCancelled(cancelled);
                if (closed) throw new IllegalStateException("Shell session manager is closed");
                if (shellSessions.size() >= 32) throw new IllegalStateException("Too many shell sessions; poll or terminate existing sessions with shell_input first");
                ensureShutdownHook();
                session = new ShellSession(java.util.UUID.randomUUID().toString(), builder.start(), child.toString(), stdoutLog, stderrLog);
                shellSessions.put(session.id, session);
            }
        } catch (IOException | RuntimeException error) {
            if (stdoutWriter != null) stdoutWriter.close();
            if (stderrWriter != null) stderrWriter.close();
            throw error;
        }
        readDirectStream(session, session.process.getInputStream(), session.stdout, stdoutWriter, "stdout");
        readDirectStream(session, session.process.getErrorStream(), session.stderr, stderrWriter, "stderr");
        startTimeout(session, timeoutSeconds == null ? 0 : timeoutSeconds);
        return pollShellSession(session, yieldMs, cancelled, false);
    }

    private static OutputStream openLog(Path path) throws IOException {
        return path == null ? null : Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void readDirectStream(ShellSession session, InputStream stream, StringBuilder buffer,
            OutputStream log, String name) {
        Thread.ofVirtual().name("codingagent-process-" + name).start(() -> {
            try (var reader = new InputStreamReader(new LoggingInputStream(stream, log, session, name), StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                int count;
                while ((count = reader.read(chunk)) != -1) {
                    synchronized (session) {
                        buffer.append(chunk, 0, count);
                        int excess = buffer.length() - BuiltInTools.MAX_BYTES / 2;
                        if (excess > 0) {
                            if (Character.isLowSurrogate(buffer.charAt(excess))) excess++;
                            buffer.delete(0, excess);
                            if (name.equals("stdout")) session.stdoutTruncated = true;
                            else session.stderrTruncated = true;
                        }
                        session.lastOutputTime = Instant.now();
                    }
                }
            } catch (IOException error) {
                session.streamError = name + ": " + error.getMessage();
            } finally {
                if (session.readersRemaining.decrementAndGet() == 0) session.outputComplete = true;
            }
        });
    }

    private static final class LoggingInputStream extends FilterInputStream {
        private OutputStream log;
        private final ShellSession session;
        private final String name;

        private LoggingInputStream(InputStream source, OutputStream log, ShellSession session, String name) {
            super(source);
            this.log = log;
            this.session = session;
            this.name = name;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) write(new byte[] {(byte) value}, 0, 1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0) write(bytes, offset, count);
            return count;
        }

        private void write(byte[] bytes, int offset, int length) {
            if (log == null) return;
            try { log.write(bytes, offset, length); }
            catch (IOException error) {
                session.logError = name + " log: " + error.getMessage();
                try { log.close(); } catch (IOException ignored) {}
                log = null;
            }
        }

        @Override public void close() throws IOException {
            try { super.close(); }
            finally {
                if (log != null) try { log.close(); }
                catch (IOException error) { session.logError = name + " log: " + error.getMessage(); }
            }
        }
    }

    /** Polls, writes literal stdin, sends EOF, or terminates an existing process. */
    public AgentTool.ToolResult interact(String id, String input, boolean closeStdin, boolean terminate,
            int yieldMs, BooleanSupplier cancelled) throws InterruptedException {
        return interact(id, input, closeStdin, terminate, false, yieldMs, cancelled);
    }

    public AgentTool.ToolResult interact(String id, String input, boolean closeStdin, boolean terminate,
            boolean processTree, int yieldMs, BooleanSupplier cancelled) throws InterruptedException {
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
        return pollShellSession(session, yieldMs, cancelled, processTree);
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
            ensureShutdownHook();
            ProcessBuilder builder = new ProcessBuilder(ShellCommandLine.arguments(shell, command))
                    .directory(cwd.toFile()).redirectErrorStream(true);
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
                        session.lastOutputTime = Instant.now();
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
        startTimeout(session, timeoutSeconds);
        return session;
    }

    private void ensureShutdownHook() {
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::closeSessions, "codingagent-shell-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    private void startTimeout(ShellSession session, double timeoutSeconds) {
        if (timeoutSeconds > 0) {
            Thread.ofVirtual().name("codingagent-shell-timeout").start(() -> {
                try {
                    while (!session.stopped && (session.process.isAlive() || !session.outputComplete)) {
                        if (System.nanoTime() - session.startedNanos >= timeoutSeconds * 1_000_000_000d) {
                            session.timedOut = true;
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
        return pollShellSession(session, yieldMs, cancelled, false);
    }

    private AgentTool.ToolResult pollShellSession(ShellSession session, int yieldMs, BooleanSupplier cancelled,
            boolean processTree) throws InterruptedException {
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
        if (session.direct) return directResult(session, finished, processTree);
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
        details.put("timed_out", session.timedOut);
        addStatus(details, session, processTree);
        String failure = session.failure;
        boolean isError = finished && failure != null;
        if (finished) {
            shellSessions.remove(session.id, session);
            String completion = "[Command stopped; exit code unavailable.]";
            if (!session.process.isAlive()) {
                int code = session.process.exitValue();
                details.put("exit_code", code);
                isError |= code != 0;
                completion = "[Command exited with code " + code + ".]";
            }
            // Providers may omit details, so completion and the exit code must also be in content.
            if (output.isBlank()) output = "(no output)";
            if (failure != null) output += "\n\n" + failure;
            output += "\n\n" + completion;
        } else if (session.stopped) {
            output += (output.isBlank() ? "" : "\n\n") + "[Shell session " + session.id
                    + " is stopping: " + failure + ". Use shell_input to collect its final result.]";
        } else {
            output += (output.isBlank() ? "" : "\n\n") + "[Shell session " + session.id
                    + " is still running. Use shell_input to poll or send input. If the output requests user information,"
                    + " ask the user and end this turn; after their reply, send it to this session, including a newline to submit it.]";
        }
        return new AgentTool.ToolResult(List.of(new TextContent(output, null)), details, isError);
    }

    private AgentTool.ToolResult directResult(ShellSession session, boolean finished, boolean processTree) {
        String stdout;
        String stderr;
        boolean stdoutTruncated;
        boolean stderrTruncated;
        synchronized (session) {
            stdoutTruncated = session.stdoutTruncated;
            stderrTruncated = session.stderrTruncated;
            stdout = boundShellOutput(session.stdout.toString(), stdoutTruncated);
            stderr = boundShellOutput(session.stderr.toString(), stderrTruncated);
            session.stdout.setLength(0);
            session.stderr.setLength(0);
            session.stdoutTruncated = false;
            session.stderrTruncated = false;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("session_id", session.id);
        details.put("status", finished ? "exited" : session.stopped ? "stopping" : "running");
        details.put("executable", session.executable);
        details.put("pid", session.process.pid());
        details.put("stdout", stdout);
        details.put("stderr", stderr);
        details.put("stdout_truncated", stdoutTruncated);
        details.put("stderr_truncated", stderrTruncated);
        details.put("input_pending", session.inputPending);
        details.put("timed_out", session.timedOut);
        if (session.stdoutLog != null) details.put("stdout_log", session.stdoutLog.toString());
        if (session.stderrLog != null) details.put("stderr_log", session.stderrLog.toString());
        if (session.logError != null) details.put("log_error", session.logError);
        if (session.streamError != null) details.put("stream_error", session.streamError);
        addStatus(details, session, processTree);
        StringBuilder content = new StringBuilder();
        if (!stdout.isEmpty()) content.append("stdout:\n").append(stdout);
        if (!stderr.isEmpty()) content.append(content.isEmpty() ? "" : "\n\n").append("stderr:\n").append(stderr);
        if (finished) {
            shellSessions.remove(session.id, session);
            if (!session.process.isAlive()) {
                int code = session.process.exitValue();
                details.put("exit_code", code);
                content.append("\n\n[").append(session.executable).append(" exited with code ").append(code).append(".]");
            } else content.append("\n\n[Process stopped; exit code unavailable.]");
            if (session.failure != null) content.append(" ").append(session.failure);
        } else {
            content.append("\n\n[Process session ").append(session.id).append(" is ")
                    .append(session.stopped ? "stopping" : "running")
                    .append(". Use shell_input to poll, send stdin, or terminate.]");
        }
        boolean isError = finished && (session.timedOut || session.failure != null
                || !session.process.isAlive() && session.process.exitValue() != 0);
        return new AgentTool.ToolResult(List.of(new TextContent(content.toString(), null)), details, isError);
    }

    private static void addStatus(Map<String, Object> details, ShellSession session, boolean processTree) {
        details.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - session.startedNanos));
        details.put("last_output_time", session.lastOutputTime == null ? null : session.lastOutputTime.toString());
        details.put("cpu_time_ms", session.process.info().totalCpuDuration()
                .map(duration -> duration.toMillis()).orElse(null));
        List<ProcessHandle> children = session.process.descendants().toList();
        details.put("child_count", children.size());
        if (processTree) {
            List<Map<String, Object>> tree = new ArrayList<>();
            tree.add(processNode(session.process.toHandle()));
            for (ProcessHandle child : children) tree.add(processNode(child));
            details.put("process_tree", tree);
        }
    }

    private static Map<String, Object> processNode(ProcessHandle handle) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("pid", handle.pid());
        node.put("parent_pid", handle.parent().map(ProcessHandle::pid).orElse(null));
        node.put("command", handle.info().command().orElse(null));
        node.put("cpu_time_ms", handle.info().totalCpuDuration().map(duration -> duration.toMillis()).orElse(null));
        return node;
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
        return session.stopped ? session.terminationComplete && session.outputComplete
                : !session.process.isAlive() && session.outputComplete;
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
        public final boolean direct;
        public final String executable;
        public final Path stdoutLog;
        public final Path stderrLog;
        public final long startedNanos = System.nanoTime();
        public final StringBuilder output = new StringBuilder();
        public final StringBuilder stdout = new StringBuilder();
        public final StringBuilder stderr = new StringBuilder();
        public final AtomicInteger readersRemaining;
        public boolean truncated;
        public boolean stdoutTruncated;
        public boolean stderrTruncated;
        public volatile Instant lastOutputTime;
        public volatile boolean timedOut;
        public volatile String logError;
        public volatile String streamError;
        public volatile boolean outputComplete;
        public volatile String failure;
        public volatile boolean inputPending;
        public boolean stdinClosed;
        public volatile boolean stopped;
        public volatile boolean terminationComplete;

        public ShellSession(String id, Process process) {
            this.id = id;
            this.process = process;
            this.direct = false;
            this.executable = null;
            this.stdoutLog = null;
            this.stderrLog = null;
            this.readersRemaining = new AtomicInteger(1);
        }

        public ShellSession(String id, Process process, String executable, Path stdoutLog, Path stderrLog) {
            this.id = id;
            this.process = process;
            this.direct = true;
            this.executable = executable;
            this.stdoutLog = stdoutLog;
            this.stderrLog = stderrLog;
            this.readersRemaining = new AtomicInteger(2);
        }
    }

}
