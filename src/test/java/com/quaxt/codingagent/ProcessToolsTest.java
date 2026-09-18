package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessToolsTest {
    @TempDir Path cwd;

    @Test
    void directExecutionPreservesArgumentsAndSeparatesStreamsFromExitStatus() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            List<String> values = List.of("space here", "'single' \"double\"", "A=B", "C:\\path\\last\\",
                    "mix \"quote\" end\\", "slash\\\"quoted", "", "line one\nline two");
            ObjectNode argv = fixture("argv");
            values.forEach(argv.withArray("arguments")::add);
            var result = execute(runtime, "run_process", argv);
            assertEquals(0, details(result).get("exit_code"));
            List<String> received = ((String) details(result).get("stdout")).lines()
                    .map(line -> new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)).toList();
            assertEquals(values, received);

            var stderr = execute(runtime, "run_process", fixture("stderr-zero"));
            assertFalse(stderr.isError);
            assertEquals(0, details(stderr).get("exit_code"));
            assertEquals("", details(stderr).get("stdout"));
            assertTrue(((String) details(stderr).get("stderr")).contains("normal stderr output"));

            var failure = execute(runtime, "run_process", fixture("false-success"));
            assertTrue(failure.isError);
            assertEquals(7, details(failure).get("exit_code"));
            assertTrue(((String) details(failure).get("stdout")).contains("BUILD SUCCESSFUL"));
            assertTrue(((String) details(failure).get("stderr")).contains("link failed"));
            assertTrue(text(failure).contains("exited with code 7"));
        }
    }

    @Test
    void completeLogsSurviveOutputTruncationAndEnvironmentIsScoped() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            Path out = cwd.resolve("out.log");
            Path err = cwd.resolve("err.log");
            var flood = fixture("flood").put("stdout_log", out.toString()).put("stderr_log", err.toString());
            var result = execute(runtime, "run_process", flood);
            assertEquals(0, details(result).get("exit_code"));
            assertTrue(((String) details(result).get("stdout")).contains("truncated"));
            assertTrue(((String) details(result).get("stderr")).contains("truncated"));
            assertTrue(Files.size(out) > 120_000);
            assertTrue(Files.size(err) > 120_000);

            var binary = execute(runtime, "run_process", fixture("binary").put("stdout_log", out.toString()));
            assertEquals(0, details(binary).get("exit_code"));
            assertArrayEquals(new byte[] {(byte) 0xff, 0, 1, 2}, Files.readAllBytes(out));

            var environment = fixture("environment").put("inherit_environment", false);
            environment.putObject("environment").put("PROCESS_TEST_SET", "child-only").put("PROCESS_TEST_REMOVED", "set-then-removed");
            environment.putArray("unset_environment").add("PROCESS_TEST_REMOVED");
            var clean = execute(runtime, "run_process", environment);
            String output = (String) details(clean).get("stdout");
            assertTrue(output.contains("SET=child-only"), output);
            assertTrue(output.contains("REMOVED=null"), output);
            assertTrue(output.contains("INHERITED=null"), output);
            assertNull(System.getenv("PROCESS_TEST_SET"));
        }
    }

    @Test
    void timeoutAndTerminationCleanUpDescendants() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            var timeout = execute(runtime, "run_process", fixture("wait").put("timeout", 0.2).put("yield_ms", 3000));
            assertTrue(timeout.isError);
            assertEquals(true, details(timeout).get("timed_out"));
            assertEquals("exited", details(timeout).get("status"));

            var started = execute(runtime, "run_process", fixture("child").put("yield_ms", 1500));
            assertEquals("running", details(started).get("status"));
            var matcher = Pattern.compile("CHILD=(\\d+)").matcher((String) details(started).get("stdout"));
            assertTrue(matcher.find(), text(started));
            long childPid = Long.parseLong(matcher.group(1));
            String id = (String) details(started).get("session_id");
            var tree = execute(runtime, "shell_input", jsonObject().put("session_id", id).put("process_tree", true).put("yield_ms", 0));
            assertTrue(details(tree).containsKey("process_tree"));
            execute(runtime, "shell_input", jsonObject().put("session_id", id).put("terminate", true));
            ProcessHandle.of(childPid).ifPresent(process -> {
                try { process.onExit().get(10, TimeUnit.SECONDS); }
                catch (Exception error) { fail(error); }
            });
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void rejectsBatchFilesAndNulArguments() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                ObjectNode batch = jsonObject().put("executable", "build.cmd");
                batch.putArray("arguments").add("a");
                assertThrows(IllegalArgumentException.class, () -> execute(runtime, "run_process",
                        batch));
            }
            var args = fixture("argv");
            args.withArray("arguments").add("bad\0arg");
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, "run_process", args));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void msys2BashReceivesMultilineScriptWhenInstalled() throws Exception {
        Path bash = Path.of("C:\\msys64\\usr\\bin\\bash.exe");
        assumeTrue(Files.isRegularFile(bash));
        try (var runtime = new CodingAgentOperations()) {
            ObjectNode args = jsonObject().put("executable", bash.toString()).put("yield_ms", 5000);
            args.putArray("arguments").add("-c").add("printf '%s\\n' 'first line'\nprintf '%s\\n' 'second line'");
            var result = execute(runtime, "run_process", args);
            assertEquals(0, details(result).get("exit_code"), text(result));
            assertTrue(((String) details(result).get("stdout")).contains("first line\nsecond line"));
        }
    }

    private ObjectNode fixture(String mode) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        String classes = Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        ObjectNode args = jsonObject().put("executable", java).put("yield_ms", 5000);
        args.putArray("arguments").add("-cp").add(classes).add(ProcessFixture.class.getName()).add(mode);
        return args;
    }

    private AgentTool.ToolResult execute(CodingAgentOperations runtime, String name, ObjectNode arguments) throws Exception {
        AgentTool tool = runtime.builtInTools(cwd, ignored -> {}).stream()
                .filter(candidate -> CodingAgentOperations.toolName(candidate).equals(name)).findFirst().orElseThrow();
        return runtime.executeTool(tool, "test", arguments, new AbortSignal(), ignored -> {});
    }

    private static Map<?, ?> details(AgentTool.ToolResult result) { return (Map<?, ?>) result.details; }
    private static String text(AgentTool.ToolResult result) { return ((TextContent) result.content.getFirst()).text; }
}
