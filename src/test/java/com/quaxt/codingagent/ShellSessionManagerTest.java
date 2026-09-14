package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.shell.ShellSessionManager;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ShellSessionManagerTest {
    @TempDir Path cwd;
    private final ShellSessionManager manager = new ShellSessionManager();

    @AfterEach
    void cleanup() {
        manager.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void silentCommandsReportCompletionAndExitCodeInText(int exitCode) throws Exception {
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", "exit " + exitCode).put("yield_ms", 5000));

        assertEquals("(no output)\n\n[Command exited with code " + exitCode + ".]", text(result));
        assertEquals("exited", details(result).get("status"));
        assertEquals(exitCode, details(result).get("exit_code"));
        assertEquals(exitCode != 0, result.isError);
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id(result))));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void silentSessionsDistinguishRunningPollsFromFinalResults(int exitCode) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String command = (windows ? "$null = [Console]::ReadLine(); " : "IFS= read -r ignored; ") + "exit " + exitCode;
        AgentTool.ToolResult initial = run("shell", jsonObject().put("command", command).put("yield_ms", 0));
        assertRunning(initial);
        AgentTool.ToolResult poll = run("shell_input", jsonObject().put("session_id", id(initial)).put("yield_ms", 0));
        assertRunning(poll);

        AgentTool.ToolResult last = run("shell_input", jsonObject().put("session_id", id(initial))
                .put("input", "\n").put("yield_ms", 5000));
        assertEquals("(no output)\n\n[Command exited with code " + exitCode + ".]", text(last));
        assertEquals("exited", details(last).get("status"));
        assertEquals(exitCode, details(last).get("exit_code"));
        assertEquals(exitCode != 0, last.isError);
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id(initial))));
    }

    @Test
    void separatesCompletionNoticeFromOutputWithoutATrailingNewline() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String command = windows ? "[Console]::Write('hello')" : "printf 'hello'";
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));

        assertFalse(result.isError, text(result));
        assertEquals("hello\n\n[Command exited with code 0.]", text(result));
    }

    @Test
    void answersMultiplePromptsWithoutNewlinesAndTreatsInputLiterally() throws Exception {
        AgentTool.ToolResult first = start("prompt");
        assertTrue(text(first).contains("Paste a link: "), text(first));
        assertEquals("running", details(first).get("status"));
        String id = id(first);
        String link = "https://example.test/café?x=$(whoami)&y='quoted';echo injected";
        AgentTool.ToolResult second = run("shell_input", jsonObject().put("session_id", id).put("input", link + "\n").put("yield_ms", 1000));
        assertTrue(text(second).contains("Received: " + link), text(second));
        assertTrue(text(second).contains("Label: "), text(second));
        assertFalse(text(second).contains("Paste a link:"));
        assertFalse(text(run("shell_input", jsonObject().put("session_id", id).put("yield_ms", 0))).contains("Received:"));
        AgentTool.ToolResult last = run("shell_input", jsonObject().put("session_id", id).put("input", "example\n").put("yield_ms", 5000));
        assertTrue(text(last).contains("Label received: example"), text(last));
        assertEquals("exited", details(last).get("status"));
        assertEquals(0, details(last).get("exit_code"));
        assertFalse(last.isError);
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id)));
    }

    @Test
    void closesStdinAfterSendingText() throws Exception {
        String id = id(start("eof"));
        AgentTool.ToolResult result = run("shell_input", jsonObject().put("session_id", id)
                .put("input", "last line\n").put("close_stdin", true).put("yield_ms", 5000));
        assertTrue(text(result).contains("Received: last line"), text(result));
        assertTrue(text(result).contains("EOF received"), text(result));
        assertEquals("exited", details(result).get("status"));
    }

    @Test
    void timeoutKillsTheScriptEvenWhenNoToolIsPolling() throws Exception {
        AgentTool.ToolResult initial = run("shell", jsonObject().put("command", ShellStdioFixture.command("wait"))
                .put("timeout", 4).put("yield_ms", 2000));
        long pid = pid(initial);
        awaitDead(pid);
        AgentTool.ToolResult result = run("shell_input", jsonObject().put("session_id", id(initial)).put("yield_ms", 5000));
        assertTrue(result.isError);
        assertTrue(text(result).contains("timed out"), text(result));
        assertExitCodeInText(result);
        assertEquals("exited", details(result).get("status"));
    }

    @Test
    void terminateKillsTheScriptAndReleasesItsSession() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        AgentTool.ToolResult result = run("shell_input", jsonObject().put("session_id", id(initial)).put("terminate", true));
        assertTrue(result.isError);
        assertTrue(text(result).contains("Command terminated"), text(result));
        assertExitCodeInText(result);
        awaitDead(pid(initial));
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id(initial))));
    }

    @Test
    void clearingSessionsKillsScriptsAndAllowsNewCommands() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        manager.closeSessions();
        awaitDead(pid(initial));
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id(initial))));
        AgentTool.ToolResult next = start("wait");
        manager.closeSessions();
        awaitDead(pid(next));
    }

    @Test
    void abortSignalInterruptsAPoll() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        AtomicBoolean signal = new AtomicBoolean();
        CompletableFuture<AgentTool.ToolResult> polling = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                polling.complete(run("shell_input", jsonObject().put("session_id", id(initial)).put("yield_ms", 30000), signal));
            } catch (Exception error) {
                polling.completeExceptionally(error);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.getState() != Thread.State.TIMED_WAITING && !polling.isDone() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(Thread.State.TIMED_WAITING, worker.getState());
        signal.set(true);
        AgentTool.ToolResult result = polling.get(5, TimeUnit.SECONDS);
        assertTrue(result.isError);
        assertTrue(text(result).contains("Command aborted"), text(result));
        assertExitCodeInText(result);
        awaitDead(pid(initial));
    }

    @Test
    void boundsUnreadOutputAndStillDrainsThePipe() throws Exception {
        AgentTool.ToolResult initial = start("flood");
        assertTrue(text(initial).contains("truncated"), text(initial));
        assertTrue(text(initial).contains("Paste a link after the log: "), text(initial));
        assertTrue(text(initial).length() < 53_000);
        AgentTool.ToolResult last = run("shell_input", jsonObject().put("session_id", id(initial)).put("input", "\n").put("yield_ms", 5000));
        assertTrue(text(last).contains("Done"), text(last));
        assertEquals("exited", details(last).get("status"));
    }

    @Test
    void completionNoticeSurvivesOutputTruncation() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String command = windows ? "[Console]::Write('x' * 60000)" : "printf 'x%.0s' {1..60000}";
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));

        assertFalse(result.isError, text(result));
        assertTrue(text(result).startsWith("[Earlier command output truncated; showing most recent output]\n"), text(result));
        assertTrue(text(result).length() < 53_000);
        assertEquals(0, details(result).get("exit_code"));
        assertExitCodeInText(result);
    }

    @Test
    void reportsNonzeroExitAndRejectsInvalidArguments() throws Exception {
        AgentTool.ToolResult result = start("fail");
        assertTrue(result.isError);
        assertTrue(text(result).contains("Script failed"), text(result));
        assertExitCodeInText(result);
        assertNotEquals(0, details(result).get("exit_code"));
        assertThrows(IllegalArgumentException.class, () -> run("shell", jsonObject().put("command", "echo ok").put("yield_ms", -1)));
        assertThrows(IllegalArgumentException.class, () -> run("shell", jsonObject().put("command", "echo ok").put("yield_ms", 30001)));
        assertThrows(IllegalArgumentException.class, () -> run("shell", jsonObject().put("command", "echo ok").put("timeout", 0)));
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", "missing")));
    }

    @Test
    void answersTheShellsOwnReadPrompt() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String command = windows ? "$link = Read-Host 'Link'; Write-Output ('Got: ' + $link)"
                : "printf 'Link: '; IFS= read -r link; printf 'Got: %s\\n' \"$link\"";
        AgentTool.ToolResult first = run("shell", jsonObject().put("command", command).put("yield_ms", 2000));
        assertTrue(text(first).contains("Link"), text(first));
        AgentTool.ToolResult last = run("shell_input", jsonObject().put("session_id", id(first))
                .put("input", "https://example.test/\n").put("yield_ms", 5000));
        assertTrue(text(last).contains("Got: https://example.test/"), text(last));
        assertFalse(last.isError);
        assertEquals("exited", details(last).get("status"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellPreservesQuotesAndInterpolation() throws Exception {
        String command = """
                $key = 'gpt-6-astra'
                "Added model: $key"
                Write-Output "embedded `"double`" quotes and 'single' quotes"
                Write-Output 'literal "quotes" $key $(Get-Date); & | < >'
                """;
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));

        assertFalse(result.isError, text(result));
        assertEquals("exited", details(result).get("status"));
        assertEquals(0, details(result).get("exit_code"));
        assertEquals("""
                Added model: gpt-6-astra
                embedded "double" quotes and 'single' quotes
                literal "quotes" $key $(Get-Date); & | < >
                """ + "\n\n[Command exited with code 0.]", text(result).replace("\r\n", "\n"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellPreservesMultilineScriptsAndUnicode() throws Exception {
        String command = """
                $value = 'café 雪 🚀'
                # This comment must end before the here-string starts.
                @"
                Unicode: $value
                Literal: `"quotes`" 'apostrophe' `$dollar ; & |
                "@ | ForEach-Object { $_.Replace('Unicode:', 'Value:') }
                @'
                Literal: "$value" `backticks` C:\\path with spaces\\
                '@
                """;
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));

        assertFalse(result.isError, text(result));
        assertEquals(0, details(result).get("exit_code"));
        assertEquals("""
                Value: café 雪 🚀
                Literal: "quotes" 'apostrophe' $dollar ; & |
                Literal: "$value" `backticks` C:\\path with spaces\\
                """ + "\n\n[Command exited with code 0.]", text(result).replace("\r\n", "\n"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellKeepsStdinAvailableForQuotedPrompts() throws Exception {
        AgentTool.ToolResult first = run("shell", jsonObject()
                .put("command", "$reply = Read-Host \"Your label\"; Write-Output \"Received: $reply\"")
                .put("yield_ms", 2000));
        assertTrue(text(first).contains("Your label: "), text(first));
        assertEquals("running", details(first).get("status"));

        String reply = "café 雪 🚀 \"quoted\" 'single' $value $(whoami); & |";
        AgentTool.ToolResult last = run("shell_input", jsonObject().put("session_id", id(first))
                .put("input", reply + "\n").put("yield_ms", 5000));
        assertFalse(last.isError, text(last));
        assertEquals("exited", details(last).get("status"));
        assertEquals(0, details(last).get("exit_code"));
        assertEquals("Received: " + reply + "\n\n\n[Command exited with code 0.]", text(last).replace("\r\n", "\n"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellReportsErrorsAsTextAndPreservesExitCodes() throws Exception {
        AgentTool.ToolResult result = run("shell", jsonObject()
                .put("command", "Write-Error 'quoted failure'; exit 7").put("yield_ms", 5000));

        assertTrue(result.isError);
        assertEquals("exited", details(result).get("status"));
        assertEquals(7, details(result).get("exit_code"));
        assertTrue(text(result).contains("quoted failure"), text(result));
        assertFalse(text(result).contains("#< CLIXML"), text(result));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellPreservesBackslashesBeforeQuotesAndAtTheEnd() throws Exception {
        String command = """
                Write-Output "C:\\path with spaces\\"
                Write-Output "\\\\server\\share\\\\"
                """ + "# trailing backslashes \\\\";
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));

        assertFalse(result.isError, text(result));
        assertEquals(0, details(result).get("exit_code"));
        assertEquals("C:\\path with spaces\\\n\\\\server\\share\\\\\n\n\n[Command exited with code 0.]", text(result).replace("\r\n", "\n"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellStillSupportsLongScripts() throws Exception {
        String command = "#".repeat(20_000) + "\nWrite-Output 'long script survived'";
        AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));

        assertFalse(result.isError, text(result));
        assertEquals(0, details(result).get("exit_code"));
        assertEquals("long script survived\n\n\n[Command exited with code 0.]", text(result).replace("\r\n", "\n"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellPreservesImplicitFailureExitStatus() throws Exception {
        for (String command : java.util.List.of("Write-Error 'failed'", "throw 'failed'", "& cmd.exe /c exit 7", "if (")) {
            AgentTool.ToolResult result = run("shell", jsonObject().put("command", command).put("yield_ms", 5000));
            assertTrue(result.isError, command + ": " + text(result));
            assertEquals(1, details(result).get("exit_code"), command);
            assertFalse(text(result).contains("#< CLIXML"), text(result));
        }
    }

    @Test
    void aFullStdinPipeDoesNotBlockPollingOrTermination() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        AgentTool.ToolResult pending = run("shell_input", jsonObject().put("session_id", id(initial))
                .put("input", "x".repeat(50_000)).put("yield_ms", 100));
        assertEquals("running", details(pending).get("status"));
        AgentTool.ToolResult stopped = run("shell_input", jsonObject().put("session_id", id(initial)).put("terminate", true));
        assertEquals("exited", details(stopped).get("status"));
        awaitDead(pid(initial));
    }

    @Test
    void managersOwnSeparateSessionsAndClosingOneDoesNotAffectTheOther() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        try (ShellSessionManager other = new ShellSessionManager()) {
            assertThrows(IllegalArgumentException.class,
                    () -> other.interact(id(initial), null, false, false, 0, () -> false));
        }
        assertEquals("running", details(run("shell_input", jsonObject().put("session_id", id(initial)).put("yield_ms", 0))).get("status"));
        manager.close();
        awaitDead(pid(initial));
        assertThrows(IllegalStateException.class, () -> start("wait"));
    }

    private AgentTool.ToolResult start(String mode) throws Exception {
        return run("shell", jsonObject().put("command", ShellStdioFixture.command(mode)).put("yield_ms", 2000));
    }

    private AgentTool.ToolResult run(String name, ObjectNode arguments) throws Exception {
        return run(name, arguments, new AtomicBoolean());
    }

    private AgentTool.ToolResult run(String name, ObjectNode arguments, AtomicBoolean signal) throws Exception {
        if (name.equals("shell")) {
            BuiltInTools.Shell shell = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? BuiltInTools.Shell.POWERSHELL : BuiltInTools.Shell.BASH;
            return manager.execute(cwd, shell, arguments.path("command").asText(),
                    arguments.has("timeout") ? arguments.path("timeout").asDouble() : null,
                    arguments.path("yield_ms").asInt(1000), signal::get);
        }
        return manager.interact(arguments.path("session_id").asText(),
                arguments.has("input") ? arguments.path("input").asText() : null,
                arguments.path("close_stdin").asBoolean(), arguments.path("terminate").asBoolean(),
                arguments.path("yield_ms").asInt(1000), signal::get);
    }

    private static Map<?, ?> details(AgentTool.ToolResult result) {
        return (Map<?, ?>) result.details;
    }

    private static String id(AgentTool.ToolResult result) {
        return (String) details(result).get("session_id");
    }

    private static String text(AgentTool.ToolResult result) {
        return ((TextContent) result.content.getFirst()).text;
    }

    private static void assertRunning(AgentTool.ToolResult result) {
        assertEquals("running", details(result).get("status"));
        assertFalse(details(result).containsKey("exit_code"));
        assertFalse(result.isError);
        assertTrue(text(result).contains("[Shell session " + id(result) + " is still running."), text(result));
        assertTrue(text(result).contains("Use shell_input"), text(result));
        assertFalse(text(result).contains("Command exited"), text(result));
    }

    private static void assertExitCodeInText(AgentTool.ToolResult result) {
        assertEquals("exited", details(result).get("status"));
        assertNotNull(details(result).get("exit_code"));
        assertTrue(text(result).endsWith("[Command exited with code " + details(result).get("exit_code") + ".]"), text(result));
        assertFalse(text(result).contains("Use shell_input"), text(result));
    }

    private static long pid(AgentTool.ToolResult result) {
        var matcher = Pattern.compile("PID=(\\d+)").matcher(text(result));
        assertTrue(matcher.find(), text(result));
        return Long.parseLong(matcher.group(1));
    }

    private static void awaitDead(long pid) throws Exception {
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) process.get().onExit().get(10, TimeUnit.SECONDS);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
