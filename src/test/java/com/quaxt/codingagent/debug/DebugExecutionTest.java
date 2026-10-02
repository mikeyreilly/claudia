package com.quaxt.codingagent.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.ai.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class DebugExecutionTest {
    private static final Path WORKSPACE = Path.of("").toAbsolutePath().normalize();
    private static final String SOURCE = "src/test/java/com/quaxt/codingagent/debug/DebugFixture.java";
    @TempDir Path temp;

    @Test
    void continueRunToAndStepReturnTheNextStopOrExitWithoutASeparateWait() throws Exception {
        try (Target target = new Target(temp.resolve("go"))) {
            target.addBreakpoint();
            Files.createFile(target.gate);
            ObjectNode hit = target.ok("debug_continue", target.stoppedArgs().put("wait_ms", 10000));
            assertEquals("stopped", hit.path("status").asText(), hit.toString());
            assertEquals("first", hit.path("stop").path("breakpoint_id").asText());
            assertFalse(hit.path("wait_expired").asBoolean());
            assertCompact(hit);
            target.stop = hit.path("stop").path("stop_id").asText();
            ObjectNode wait = target.ok("debug_wait", target.sessionArgs().put("wait_ms", 0));
            assertEquals(target.stop, wait.path("stop").path("stop_id").asText());
            assertCompact(wait);

            ObjectNode runTo = target.ok("debug_run_to", target.stoppedArgs().put("source_path", SOURCE)
                    .put("class_name", DebugFixture.class.getName()).put("line", line("DEBUG_FIXTURE_RETURN"))
                    .put("wait_ms", 10000));
            assertEquals("stopped", runTo.path("status").asText(), runTo.toString());
            assertEquals(line("DEBUG_FIXTURE_RETURN"), runTo.path("stop").path("location").path("line").asInt());
            assertCompact(runTo);
            target.stop = runTo.path("stop").path("stop_id").asText();
            assertEquals(0, target.ok("debug_breakpoints", target.sessionArgs()).path("breakpoints").size());

            ObjectNode step = target.ok("debug_step", target.stoppedArgs().put("direction", "out").put("wait_ms", 10000));
            assertEquals("stopped", step.path("status").asText(), step.toString());
            assertEquals("step", step.path("stop").path("reason").asText());
            assertCompact(step);
            target.stop = step.path("stop").path("stop_id").asText();
            ObjectNode completed = target.ok("debug_continue", target.stoppedArgs().put("wait_ms", 10000));
            assertEquals("completed", completed.path("status").asText(), completed.toString());
            assertFalse(completed.has("stop"));
            assertTrue(completed.has("completion_reason"));
            assertCompact(completed);
            ObjectNode detailed = target.ok("debug_status", target.sessionArgs());
            assertTrue(detailed.has("capabilities"));
            assertTrue(detailed.has("arguments"));
            assertTrue(detailed.has("breakpoints"));
        }
    }

    @Test
    void zeroAndExpiredWaitsLeaveTheTargetRunningAndLaterWaitReturnsAStop() throws Exception {
        try (Target target = new Target(temp.resolve("timeout-go"))) {
            target.addBreakpoint();
            ObjectNode running = target.ok("debug_continue", target.stoppedArgs().put("wait_ms", 0));
            assertEquals("running", running.path("status").asText(), running.toString());
            assertTrue(running.path("wait_expired").asBoolean());
            assertCompact(running);
            long started = System.nanoTime();
            ObjectNode waitArgs = target.sessionArgs().put("cursor", running.path("event_cursor").asInt()).put("wait_ms", 120);
            waitArgs.putArray("event_types").add("stop");
            ObjectNode expired = target.ok("debug_wait", waitArgs);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("running", expired.path("status").asText());
            assertTrue(expired.path("wait_expired").asBoolean());
            assertTrue(elapsedMs >= 100 && elapsedMs < 5000, "Bounded wait took " + elapsedMs + "ms");
            assertTrue(target.process.isAlive());
            assertFalse(expired.has("stop"));
            assertCompact(expired);

            ObjectNode paused = target.ok("debug_pause", target.sessionArgs());
            assertEquals("pause", paused.path("stop").path("reason").asText());
            assertCompact(paused);
            target.stop = paused.path("stop").path("stop_id").asText();
            started = System.nanoTime();
            expired = target.ok("debug_continue", target.stoppedArgs().put("wait_ms", 120));
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("running", expired.path("status").asText());
            assertTrue(expired.path("wait_expired").asBoolean());
            assertTrue(elapsedMs >= 100 && elapsedMs < 5000, "Execution wait took " + elapsedMs + "ms");
            assertFalse(target.ok("debug_status", target.sessionArgs()).has("stop"));
            Files.createFile(target.gate);
            ObjectNode stopped = target.ok("debug_wait", target.sessionArgs().put("cursor", expired.path("event_cursor").asInt())
                    .put("wait_ms", 10000).set("event_types", Json.MAPPER.valueToTree(List.of("stop"))));
            assertEquals("stopped", stopped.path("status").asText(), stopped.toString());
            assertEquals("first", stopped.path("stop").path("breakpoint_id").asText());
        }
    }

    @Test
    void invalidWaitIsRejectedBeforeAnyExecutionOrRunToBreakpointChanges() throws Exception {
        try (Target target = new Target(temp.resolve("invalid-go"))) {
            for (String operation : List.of("debug_continue", "debug_step", "debug_run_to")) {
                for (JsonNode wait : List.<JsonNode>of(Json.MAPPER.valueToTree(-1), Json.MAPPER.valueToTree(30001),
                        Json.MAPPER.valueToTree(new java.math.BigInteger("18446744073709551616")),
                        Json.MAPPER.valueToTree("0"), Json.MAPPER.valueToTree(0.5))) {
                    ObjectNode args = target.stoppedArgs().put("source_path", SOURCE)
                            .put("class_name", DebugFixture.class.getName()).put("line", line("DEBUG_FIXTURE_BREAKPOINT"));
                    args.set("wait_ms", wait);
                    ObjectNode rejected = target.call(operation, args);
                    assertEquals("invalid_argument", rejected.path("code").asText(), rejected.toString());
                    assertEquals(target.stop, target.ok("debug_status", target.sessionArgs()).path("stop").path("stop_id").asText());
                    assertEquals(0, target.ok("debug_breakpoints", target.sessionArgs()).path("breakpoints").size());
                }
            }
        }
    }

    @Test
    void cancellationAfterResumeDoesNotPauseOrTerminateAndReplayDoesNotResumeTwice() throws Exception {
        try (Target target = new Target(temp.resolve("cancel-go"))) {
            AtomicInteger polls = new AtomicInteger();
            ObjectNode args = target.stoppedArgs().put("wait_ms", 30000).put("idempotency_key", "cancel-once");
            ObjectNode cancelled = target.manager.call("debug_continue", args, WORKSPACE, () -> polls.incrementAndGet() > 1);
            assertEquals("cancelled", cancelled.path("code").asText(), cancelled.toString());
            assertTrue(cancelled.path("message").asText().contains("was resumed"));
            assertEquals("running", target.ok("debug_status", target.sessionArgs()).path("status").asText());
            assertTrue(target.process.isAlive());
            assertEquals(cancelled, target.call("debug_continue", args.deepCopy()));
            ObjectNode events = target.ok("debug_events", target.sessionArgs());
            int resumes = 0;
            for (JsonNode event : events.path("events")) if (event.path("type").asText().equals("resume")) resumes++;
            assertEquals(1, resumes);
        }
    }

    private static void assertCompact(ObjectNode response) {
        for (String key : List.of("capabilities", "arguments", "jvm_options", "environment_overrides", "target", "ownership",
                "breakpoints", "breakpoint_count", "breakpoints_truncated"))
            assertFalse(response.has(key), "Execution response repeats " + key + ": " + response);
        assertTrue(response.has("session_id"));
        assertTrue(response.has("pid"));
        assertTrue(response.has("event_cursor"));
        assertTrue(response.has("output_cursor"));
    }

    private static int line(String marker) throws Exception {
        List<String> lines = Files.readAllLines(WORKSPACE.resolve(SOURCE));
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("// " + marker)) return i + 1;
        throw new AssertionError("Missing " + marker);
    }

    private static ObjectNode obj() { return Json.MAPPER.createObjectNode(); }

    private static final class Target implements AutoCloseable {
        final DebugManager manager = new DebugManager();
        final Path gate;
        String session, stop;
        ProcessHandle process;

        Target(Path gate) throws Exception {
            this.gate = gate;
            try {
                ObjectNode launch = obj().put("main_class", DebugFixture.class.getName()).put("stop_on_entry", true);
                launch.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
                launch.putArray("arguments").add(gate.toString());
                ObjectNode started = ok("debug_launch", launch);
                session = started.path("session_id").asText();
                process = ProcessHandle.of(started.path("pid").asLong()).orElseThrow();
                ObjectNode entry = ok("debug_wait", sessionArgs().put("wait_ms", 10000));
                assertEquals("stopped", entry.path("status").asText(), entry.toString());
                stop = entry.path("stop").path("stop_id").asText();
            } catch (Exception | AssertionError failure) {
                close(); throw failure;
            }
        }

        ObjectNode call(String operation, ObjectNode args) { return manager.call(operation, args, WORKSPACE); }
        ObjectNode ok(String operation, ObjectNode args) {
            ObjectNode response = call(operation, args);
            assertNotEquals("error", response.path("status").asText(), operation + ": " + response);
            return response;
        }
        ObjectNode sessionArgs() { return obj().put("session_id", session); }
        ObjectNode stoppedArgs() { return sessionArgs().put("stop_id", stop); }
        void addBreakpoint() throws Exception {
            ObjectNode args = sessionArgs().put("action", "add");
            args.putArray("breakpoints").add(obj().put("breakpoint_id", "first").put("source_path", SOURCE)
                    .put("line", line("DEBUG_FIXTURE_BREAKPOINT")).put("one_shot", true));
            ok("debug_breakpoints", args);
        }

        @Override public void close() {
            try {
                if (session != null) call("debug_detach", sessionArgs().put("terminate", true).put("leave_running", false).put("close_session", true));
            } finally {
                manager.close();
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
