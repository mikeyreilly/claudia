package com.quaxt.codingagent.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.ToolDefinition;
import com.quaxt.codingagent.agent.ToolInvocation;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.util.AbortSignal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DebugManagerTest {
    private static final Path WORKSPACE = Path.of("").toAbsolutePath().normalize();
    private static final String SOURCE = "src/test/java/com/quaxt/codingagent/debug/DebugFixture.java";
    @TempDir Path temp;

    @Test
    @Timeout(45)
    void launchEntryPauseBreakpointInspectOutputAndDetach() throws Exception {
        Path gate = temp.resolve("go");
        long pid = -1;
        try (DebugManager manager = new DebugManager()) {
            ObjectNode launch = obj().put("main_class", DebugFixture.class.getName()).put("stop_on_entry", true);
            launch.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
            launch.putArray("arguments").add(gate.toString());
            ObjectNode started = manager.call("debug_launch", launch, WORKSPACE);
            assertNotEquals("error", started.path("status").asText(), started.toString());
            String session = started.path("session_id").asText();
            assertFalse(session.isBlank(), started.toString());
            pid = started.path("pid").asLong();
            assertTrue(pid > 0, started.toString());
            assertEquals("launched", started.path("ownership").asText());
            assertTrue(started.path("capabilities").path("jdi").asBoolean(), started.toString());

            ObjectNode entry = awaitState(manager, session, "stopped", Duration.ofSeconds(10));
            String entryStop = entry.path("stop").path("stop_id").asText();
            assertEquals("entry", entry.path("stop").path("reason").asText(), entry.toString());
            assertTrue(manager.call("debug_sessions", obj(), WORKSPACE).path("sessions").toString().contains(session));
            ObjectNode running = ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", entryStop));
            assertEquals("running", running.path("status").asText(), running.toString());
            assertEquals("stale_stop", manager.call("debug_stack", obj().put("session_id", session).put("stop_id", entryStop), WORKSPACE).path("code").asText());
            awaitOutput(manager, session, "DEBUG_FIXTURE_READY", Duration.ofSeconds(10));

            ObjectNode paused = ok(manager, "debug_pause", obj().put("session_id", session));
            assertEquals("pause", paused.path("stop").path("reason").asText(), paused.toString());
            String pauseStop = paused.path("stop").path("stop_id").asText();
            assertEquals(pauseStop, ok(manager, "debug_status", obj().put("session_id", session)).path("stop").path("stop_id").asText());
            int line = breakpointLine();
            ObjectNode spec = obj().put("session_id", session).put("action", "add")
                    .put("breakpoint_id", "fixture-line").put("type", "source")
                    .put("source_path", SOURCE).put("class_name", DebugFixture.class.getName()).put("line", line);
            ObjectNode added = ok(manager, "debug_breakpoints", spec);
            assertEquals(1, added.path("breakpoints").size(), added.toString());
            assertEquals("verified", added.path("breakpoints").get(0).path("verification").asText(), added.toString());
            assertEquals(1, ok(manager, "debug_breakpoints", obj().put("session_id", session)).path("breakpoints").size());
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", pauseStop));
            assertEquals("stale_stop", manager.call("debug_continue", obj().put("session_id", session).put("stop_id", pauseStop), WORKSPACE).path("code").asText());
            Files.createFile(gate);

            ObjectNode hit = awaitState(manager, session, "stopped", Duration.ofSeconds(10));
            assertEquals("breakpoint", hit.path("stop").path("reason").asText(), hit.toString());
            assertEquals("fixture-line", hit.path("stop").path("breakpoint_id").asText(), hit.toString());
            String stop = hit.path("stop").path("stop_id").asText();
            assertNotEquals(pauseStop, stop);
            ObjectNode stack = manager.call("debug_stack", obj().put("session_id", session).put("stop_id", stop).put("filter", DebugFixture.class.getName()), WORKSPACE);
            JsonNode frame = stack.path("frames").get(0);
            // Probe locals even if stack inspection fails, so the test reports independent contracts.
            String frameId = frame == null ? stop + ":" + hit.path("stop").path("thread_id").asText() + ":0"
                    : frame.path("frame_id").asText();
            ObjectNode locals = manager.call("debug_variables", obj().put("session_id", session).put("stop_id", stop).put("frame_id", frameId), WORKSPACE);
            boolean localsPresent = containsVariable(locals.path("variables"), "seed", "40");
            assertEquals("stale_stop", manager.call("debug_variables", obj().put("session_id", session).put("stop_id", pauseStop), WORKSPACE).path("code").asText());

            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", stop));
            awaitOutput(manager, session, "DEBUG_FIXTURE_DONE=42", Duration.ofSeconds(10));
            assertEquals("completed", awaitState(manager, session, "completed", Duration.ofSeconds(10)).path("status").asText());
            ObjectNode detached = ok(manager, "debug_detach", obj().put("session_id", session).put("leave_running", true));
            assertEquals("completed", detached.path("status").asText(), detached.toString());
            assertFalse(detached.path("terminated").asBoolean(), detached.toString());
            assertAll("inspection at fixture breakpoint",
                    () -> assertEquals("ok", stack.path("status").asText(), stack.toString()),
                    () -> { if (!"error".equals(stack.path("status").asText())) assertNotNull(frame, stack.toString()); },
                    () -> { if (frame != null) assertEquals("compute", frame.path("method_name").asText(), stack.toString()); },
                    () -> { if (frame != null) assertEquals(line, frame.path("line").asInt(), stack.toString()); },
                    () -> assertEquals("ok", locals.path("status").asText(), locals.toString()),
                    () -> assertTrue(localsPresent, locals.toString()));
        } finally {
            cleanupFixture(gate, pid);
        }
    }

    @Test
    @Timeout(30)
    void detachFromStoppedLaunchLeavesOnlyOurTargetRunning() throws Exception {
        Path gate = temp.resolve("detach-go");
        ProcessHandle target = null;
        try (DebugManager manager = new DebugManager()) {
            ObjectNode args = obj().put("main_class", DebugFixture.class.getName()).put("stop_on_entry", true);
            args.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
            args.putArray("arguments").add(gate.toString());
            ObjectNode launch = ok(manager, "debug_launch", args);
            target = ProcessHandle.of(launch.path("pid").asLong()).orElseThrow();
            String session = launch.path("session_id").asText();
            String stop = awaitState(manager, session, "stopped", Duration.ofSeconds(10)).path("stop").path("stop_id").asText();
            ObjectNode detached = ok(manager, "debug_detach", obj().put("session_id", session).put("leave_running", true));
            assertEquals("completed", detached.path("status").asText());
            assertFalse(detached.path("terminated").asBoolean());
            assertEquals("stale_stop", manager.call("debug_continue", obj().put("session_id", session).put("stop_id", stop), WORKSPACE).path("code").asText());
            Files.createFile(gate);
            target.onExit().get(10, TimeUnit.SECONDS);
            assertFalse(target.isAlive(), "Detached fixture should run to completion, not remain suspended");
        } finally {
            cleanupFixture(gate, target == null ? -1 : target.pid());
        }
    }

    @Test
    @Timeout(30)
    void mainLaunchHonorsWorkspaceAndRedactsSensitiveEnvironmentOverrides() throws Exception {
        Path gate = temp.resolve("go");
        long pid = -1;
        try (DebugManager manager = new DebugManager()) {
            ObjectNode args = obj().put("main_class", DebugFixture.class.getName())
                    .put("working_directory", temp.toString()).put("stop_on_entry", true);
            args.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
            args.putArray("arguments").add(gate.toString());
            args.putObject("environment").put("DEBUG_SECRET", "private-credential-123");
            ObjectNode launch = ok(manager, "debug_launch", args);
            pid = launch.path("pid").asLong();
            String session = launch.path("session_id").asText();
            String stop = awaitState(manager, session, "stopped", Duration.ofSeconds(10)).path("stop").path("stop_id").asText();
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", stop));
            Files.createFile(gate);
            awaitOutput(manager, session, "[REDACTED]", Duration.ofSeconds(10));
            ObjectNode output = ok(manager, "debug_output", obj().put("session_id", session));
            assertFalse(output.toString().contains("private-credential-123"), output.toString());
            awaitState(manager, session, "completed", Duration.ofSeconds(10));
            ok(manager, "debug_detach", obj().put("session_id", session).put("leave_running", true));
            assertEquals("completed", ok(manager, "debug_status", obj().put("session_id", session)).path("status").asText());
        } finally { cleanupFixture(gate, pid); }
    }

    @Test
    @Timeout(45)
    void runToStepAndReadOnlyInspectionAreStopScoped() throws Exception {
        Path gate = temp.resolve("inspect-go");
        long pid = -1;
        try (DebugManager manager = new DebugManager()) {
            ObjectNode launch = launchFixture(manager, gate);
            pid = launch.path("pid").asLong();
            String session = launch.path("session_id").asText();
            String entry = awaitState(manager, session, "stopped", Duration.ofSeconds(10)).path("stop").path("stop_id").asText();
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", entry));
            awaitOutput(manager, session, "DEBUG_FIXTURE_READY", Duration.ofSeconds(10));
            String pause = ok(manager, "debug_pause", obj().put("session_id", session)).path("stop").path("stop_id").asText();
            ObjectNode run = ok(manager, "debug_run_to", obj().put("session_id", session).put("stop_id", pause)
                    .put("source_path", SOURCE).put("class_name", DebugFixture.class.getName()).put("line", breakpointLine()));
            assertEquals("running", run.path("status").asText(), run.toString());
            Files.createFile(gate);
            ObjectNode hit = awaitState(manager, session, "stopped", Duration.ofSeconds(10));
            assertEquals("breakpoint", hit.path("stop").path("reason").asText(), hit.toString());
            String stop = hit.path("stop").path("stop_id").asText();
            assertEquals(0, ok(manager, "debug_breakpoints", obj().put("session_id", session)).path("breakpoints").size(),
                    "Run-to breakpoint must be one-shot");
            ObjectNode base = obj().put("session_id", session).put("stop_id", stop);
            ObjectNode stack = ok(manager, "debug_stack", base.deepCopy().put("filter", DebugFixture.class.getName()));
            String frame = stack.path("frames").get(0).path("frame_id").asText();
            ObjectNode frameArgs = base.deepCopy().put("frame_id", frame);
            ObjectNode vars = ok(manager, "debug_variables", frameArgs.deepCopy());
            assertTrue(containsVariable(vars.path("variables"), "seed", "40"), vars.toString());
            String reference = variable(vars, "sample").path("reference").asText();
            String array = variable(vars, "numbers").path("reference").asText();
            assertFalse(reference.isBlank(), vars.toString());
            ObjectNode fields = ok(manager, "debug_object", base.deepCopy().put("reference", reference));
            assertTrue(containsVariable(fields.path("fields"), "count", "40"), fields.toString());
            assertEquals("redacted", variable(fields, "secretToken").path("availability").asText(), fields.toString());
            ObjectNode numbers = ok(manager, "debug_object", base.deepCopy().put("reference", array).put("limit", 1));
            assertEquals(2, numbers.path("length").asInt(), numbers.toString());
            assertTrue(numbers.path("truncated").asBoolean(), numbers.toString());
            assertEquals(1, numbers.path("next_cursor").asInt(), numbers.toString());
            assertEquals("42", ok(manager, "debug_object", base.deepCopy().put("reference", array).put("cursor", 1))
                    .path("fields").get(0).path("preview").asText());
            ObjectNode source = ok(manager, "debug_source", frameArgs.deepCopy().put("source_path", SOURCE).put("before", 0).put("after", 0));
            assertEquals(breakpointLine(), source.path("line").asInt(), source.toString());
            assertTrue(source.path("lines").get(0).path("text").asText().contains("DEBUG_FIXTURE_BREAKPOINT"), source.toString());
            assertEquals("source_mismatch", manager.call("debug_source", frameArgs.deepCopy().put("source_path", "pom.xml"), WORKSPACE).path("code").asText());
            assertEquals("no_exception", manager.call("debug_exception", base.deepCopy(), WORKSPACE).path("code").asText());
            ObjectNode evaluated = ok(manager, "debug_evaluate", frameArgs.deepCopy().put("expression", "sample.count == 40"));
            assertEquals("read_only", evaluated.path("safety_level").asText(), evaluated.toString());
            assertEquals("true", evaluated.path("value").path("preview").asText(), evaluated.toString());
            for (String expression : List.of("sample.count = 99", "sample.toString()", "new Object()"))
                assertEquals("unsafe_expression", manager.call("debug_evaluate", frameArgs.deepCopy().put("expression", expression), WORKSPACE).path("code").asText(), expression);
            assertEquals("unsupported", manager.call("debug_evaluate", frameArgs.deepCopy().put("expression", "seed")
                    .put("allow_side_effects", true), WORKSPACE).path("code").asText());
            assertEquals("40", ok(manager, "debug_evaluate", frameArgs.deepCopy().put("expression", "sample.count"))
                    .path("value").path("preview").asText(), "Rejected expressions must not mutate the target");
            ObjectNode stepped = ok(manager, "debug_step", base.deepCopy().put("direction", "over").put("wait_ms", 10000));
            assertEquals("stopped", stepped.path("status").asText(), stepped.toString());
            ObjectNode next = stepped;
            assertEquals("step", next.path("stop").path("reason").asText(), next.toString());
            String nextStop = next.path("stop").path("stop_id").asText();
            assertNotEquals(stop, nextStop);
            assertEquals("stale_stop", manager.call("debug_source", frameArgs, WORKSPACE).path("code").asText());
            assertEquals("stale_stop", manager.call("debug_exception", base.deepCopy(), WORKSPACE).path("code").asText());
            assertEquals("stale_stop", manager.call("debug_evaluate", frameArgs.deepCopy().put("expression", "seed"), WORKSPACE).path("code").asText());
            assertEquals("stale_reference", manager.call("debug_object", obj().put("session_id", session)
                    .put("stop_id", nextStop).put("reference", reference), WORKSPACE).path("code").asText());
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", nextStop));
            awaitState(manager, session, "completed", Duration.ofSeconds(10));
        } finally { cleanupFixture(gate, pid); }
    }

    @Test
    @Timeout(40)
    void exceptionInspectionIsAvailableOnlyAtTheThrowStop() throws Exception {
        Path gate = temp.resolve("throw-go");
        long pid = -1;
        try (DebugManager manager = new DebugManager()) {
            ObjectNode launch = launchFixture(manager, gate, "throw");
            pid = launch.path("pid").asLong();
            String session = launch.path("session_id").asText();
            String entry = awaitState(manager, session, "stopped", Duration.ofSeconds(10)).path("stop").path("stop_id").asText();
            ObjectNode bp = ok(manager, "debug_breakpoints", obj().put("session_id", session).put("action", "add")
                    .put("breakpoint_id", "fixture-exception").put("type", "exception")
                    .put("exception_class", "java.lang.IllegalStateException").put("caught", false));
            assertEquals("verified", bp.path("breakpoints").get(0).path("verification").asText(), bp.toString());
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", entry));
            Files.createFile(gate);
            ObjectNode hit = awaitState(manager, session, "stopped", Duration.ofSeconds(10));
            assertEquals("exception", hit.path("stop").path("reason").asText(), hit.toString());
            String stop = hit.path("stop").path("stop_id").asText();
            ObjectNode exception = ok(manager, "debug_exception", obj().put("session_id", session).put("stop_id", stop));
            assertEquals("java.lang.IllegalStateException", exception.path("type").asText(), exception.toString());
            assertEquals("fixture failure", exception.path("message").asText(), exception.toString());
            assertEquals("java.lang.IllegalArgumentException", exception.path("causes").get(0).path("type").asText(), exception.toString());
            assertEquals(DebugFixture.class.getName(), exception.path("throw_site").path("class_name").asText(), exception.toString());
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", stop));
            assertEquals("stale_stop", manager.call("debug_exception", obj().put("session_id", session).put("stop_id", stop), WORKSPACE).path("code").asText());
            awaitState(manager, session, "completed", Duration.ofSeconds(10));
        } finally { cleanupFixture(gate, pid); }
    }

    @Test
    @Timeout(35)
    void idempotencyAndEventOutputCursorsAreStable() throws Exception {
        Path gate = temp.resolve("page-go");
        long pid = -1;
        try (DebugManager manager = new DebugManager()) {
            ObjectNode launch = launchFixture(manager, gate);
            pid = launch.path("pid").asLong();
            String session = launch.path("session_id").asText();
            String entry = awaitState(manager, session, "stopped", Duration.ofSeconds(10)).path("stop").path("stop_id").asText();
            ObjectNode args = obj().put("session_id", session).put("stop_id", entry).put("idempotency_key", "resume-once");
            ObjectNode resumed = ok(manager, "debug_continue", args);
            assertEquals(resumed, ok(manager, "debug_continue", args.deepCopy()));
            assertEquals("idempotency_conflict", manager.call("debug_continue", args.deepCopy().put("stop_id", "different"), WORKSPACE).path("code").asText());
            awaitOutput(manager, session, "DEBUG_FIXTURE_READY", Duration.ofSeconds(10));
            Files.createFile(gate);
            awaitOutput(manager, session, "DEBUG_FIXTURE_DONE=42", Duration.ofSeconds(10));
            awaitState(manager, session, "completed", Duration.ofSeconds(10));
            ObjectNode first = ok(manager, "debug_events", obj().put("session_id", session).put("limit", 1));
            assertTrue(first.path("truncated").asBoolean(), first.toString());
            int cursor = first.path("next_cursor").asInt();
            assertEquals(first.path("events").get(0).path("cursor").asInt(), cursor, first.toString());
            ObjectNode rest = ok(manager, "debug_events", obj().put("session_id", session).put("cursor", cursor));
            assertFalse(rest.path("gap").asBoolean(), rest.toString());
            assertTrue(rest.path("events").size() > 0, rest.toString());
            assertTrue(rest.path("events").get(0).path("cursor").asInt() > cursor, rest.toString());
            ObjectNode filter = obj().put("session_id", session);
            filter.putArray("event_types").add("exit");
            ObjectNode filtered = ok(manager, "debug_events", filter);
            assertEquals(1, filtered.path("events").size(), filtered.toString());
            assertEquals("exit", filtered.path("events").get(0).path("type").asText());
            ObjectNode output = ok(manager, "debug_output", obj().put("session_id", session).put("limit", 1));
            assertTrue(output.path("truncated").asBoolean(), output.toString());
            int outputCursor = output.path("next_cursor").asInt();
            ObjectNode tail = ok(manager, "debug_output", obj().put("session_id", session).put("cursor", outputCursor));
            assertFalse(tail.path("gap").asBoolean(), tail.toString());
            assertTrue(tail.path("output").toString().contains("DEBUG_FIXTURE_DONE=42"), tail.toString());
            ObjectNode suppressed = ok(manager, "debug_output", obj().put("session_id", session).put("suppress_output", true));
            assertEquals("[suppressed]", suppressed.path("output").get(0).path("text").asText());
            assertEquals("invalid_argument", manager.call("debug_output", obj().put("session_id", session).put("byte_limit", 0), WORKSPACE).path("code").asText());
        } finally { cleanupFixture(gate, pid); }
    }

    @Test
    void bindingRegistersDebugToolsAndDispatchesThroughManager() throws Exception {
        try (DebugManager manager = new DebugManager()) {
            List<AgentTool> tools = DebugTools.bind(manager, WORKSPACE);
            Set<String> names = tools.stream().map(tool -> ((ToolDefinition.Bound<?>) tool).definition().name()).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("debug_launch", "debug_attach", "debug_sessions", "debug_status", "debug_detach",
                    "debug_breakpoints", "debug_continue", "debug_step", "debug_run_to", "debug_pause", "debug_wait",
                    "debug_events", "debug_threads", "debug_stack", "debug_variables", "debug_object", "debug_source",
                    "debug_exception", "debug_evaluate", "debug_output"), names);
            ToolDefinition.Bound<?> sessions = (ToolDefinition.Bound<?>) tools.stream()
                    .filter(tool -> ((ToolDefinition.Bound<?>) tool).definition().name().equals("debug_sessions"))
                    .findFirst().orElseThrow();
            AgentTool.ToolResult result = sessions.execute(new ToolInvocation("test", obj(), new AbortSignal(), ignored -> {}));
            assertFalse(result.isError);
            assertEquals("ok", ((ObjectNode) result.details).path("status").asText());
            assertEquals(0, ((ObjectNode) result.details).path("sessions").size());
        }
    }

    private static ObjectNode launchFixture(DebugManager manager, Path gate, String... extraArgs) {
        ObjectNode args = obj().put("main_class", DebugFixture.class.getName()).put("stop_on_entry", true);
        args.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
        var programArgs = args.putArray("arguments").add(gate.toString());
        for (String extra : extraArgs) programArgs.add(extra);
        return ok(manager, "debug_launch", args);
    }

    private static JsonNode variable(JsonNode variables, String name) {
        if (variables.isObject()) variables = variables.has("variables") ? variables.path("variables") : variables.path("fields");
        for (JsonNode variable : variables) if (name.equals(variable.path("name").asText())) return variable;
        fail("Missing " + name + " in " + variables);
        return null;
    }

    private static void cleanupFixture(Path gate, long pid) {
        // A failed launch may start the child before returning an error without its PID.
        // The unique gate argument identifies only this test's fixture JVM in that case.
        try (var processes = ProcessHandle.allProcesses()) {
            processes.filter(handle -> handle.pid() == pid || handle.info().commandLine()
                    .filter(command -> command.contains(DebugFixture.class.getName()) && command.contains(gate.toString()))
                    .isPresent()).forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroy();
                    try { handle.onExit().get(2, TimeUnit.SECONDS); }
                    catch (Exception ignored) { handle.destroyForcibly(); }
                }
            });
        }
    }

    private static ObjectNode obj() { return Json.MAPPER.createObjectNode(); }

    private static ObjectNode ok(DebugManager manager, String operation, ObjectNode args) {
        ObjectNode result = manager.call(operation, args, WORKSPACE);
        assertNotEquals("error", result.path("status").asText(), operation + ": " + result);
        return result;
    }

    private static ObjectNode awaitState(DebugManager manager, String session, String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        ObjectNode status = null;
        while (System.nanoTime() < deadline) {
            status = ok(manager, "debug_status", obj().put("session_id", session));
            if (expected.equals(status.path("status").asText())) return status;
            if ("completed".equals(status.path("status").asText())) fail("Target exited before " + expected + ": " + status);
            ObjectNode waited = ok(manager, "debug_wait", obj().put("session_id", session)
                    .put("cursor", status.path("event_cursor").asInt()).put("wait_ms", 200));
            if (expected.equals(waited.path("status").asText())) return waited;
        }
        fail("Timed out waiting for " + expected + ": " + status);
        return status;
    }

    private static void awaitOutput(DebugManager manager, String session, String text, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder seen = new StringBuilder();
        int cursor = 0;
        while (System.nanoTime() < deadline) {
            ObjectNode output = ok(manager, "debug_output", obj().put("session_id", session).put("cursor", cursor));
            for (JsonNode item : output.path("output")) {
                seen.append(item.path("text").asText());
                cursor = item.path("cursor").asInt();
            }
            if (seen.toString().contains(text)) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for output " + text + "; received: " + seen);
    }

    private static int breakpointLine() throws Exception {
        List<String> lines = Files.readAllLines(WORKSPACE.resolve(SOURCE));
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("// DEBUG_FIXTURE_BREAKPOINT")) return i + 1;
        throw new AssertionError("Fixture breakpoint line missing");
    }

    private static boolean containsVariable(JsonNode variables, String name, String preview) {
        for (JsonNode variable : variables)
            if (name.equals(variable.path("name").asText()) && preview.equals(variable.path("preview").asText())) return true;
        return false;
    }
}
