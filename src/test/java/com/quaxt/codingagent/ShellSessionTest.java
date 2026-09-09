package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ShellSessionTest {
    @TempDir Path cwd;
    private final CodingAgentOperations runtime = CodingAgentOperations.INSTANCE;

    @AfterEach
    void cleanup() {
        runtime.closeShellSessions();
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
        assertEquals("exited", details(result).get("status"));
    }

    @Test
    void terminateKillsTheScriptAndReleasesItsSession() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        AgentTool.ToolResult result = run("shell_input", jsonObject().put("session_id", id(initial)).put("terminate", true));
        assertTrue(text(result).contains("Command terminated"), text(result));
        awaitDead(pid(initial));
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id(initial))));
    }

    @Test
    void abortAndCloseKillScriptsWaitingBetweenTurns() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        runtime.abort();
        awaitDead(pid(initial));
        assertThrows(IllegalArgumentException.class, () -> run("shell_input", jsonObject().put("session_id", id(initial))));
        AgentTool.ToolResult next = start("wait");
        runtime.closeShellSessions();
        awaitDead(pid(next));
    }

    @Test
    void abortSignalInterruptsAPoll() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        AbortSignal signal = new AbortSignal();
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
        runtime.abort(signal);
        assertTrue(polling.get(5, TimeUnit.SECONDS).isError);
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
    void reportsNonzeroExitAndRejectsInvalidArguments() throws Exception {
        AgentTool.ToolResult result = start("fail");
        assertTrue(result.isError);
        assertTrue(text(result).contains("Script failed"), text(result));
        assertTrue(text(result).contains("Command exited with code"), text(result));
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
    void aFullStdinPipeDoesNotBlockPollingOrTermination() throws Exception {
        AgentTool.ToolResult initial = start("wait");
        AgentTool.ToolResult pending = run("shell_input", jsonObject().put("session_id", id(initial))
                .put("input", "x".repeat(50_000)).put("yield_ms", 100));
        assertEquals("running", details(pending).get("status"));
        AgentTool.ToolResult stopped = run("shell_input", jsonObject().put("session_id", id(initial)).put("terminate", true));
        assertEquals("exited", details(stopped).get("status"));
        awaitDead(pid(initial));
    }

    private AgentTool.ToolResult start(String mode) throws Exception {
        return run("shell", jsonObject().put("command", ShellStdioFixture.command(mode)).put("yield_ms", 2000));
    }

    private AgentTool.ToolResult run(String name, ObjectNode arguments) throws Exception {
        return run(name, arguments, new AbortSignal());
    }

    private AgentTool.ToolResult run(String name, ObjectNode arguments, AbortSignal signal) throws Exception {
        AgentTool tool = runtime.builtInTools(cwd, ignored -> {}).stream()
                .filter(candidate -> CodingAgentOperations.toolName(candidate).equals(name)).findFirst().orElseThrow();
        return runtime.executeTool(tool, "test", arguments, signal, ignored -> {});
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
