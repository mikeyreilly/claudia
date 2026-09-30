package com.quaxt.codingagent.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.ToolDefinition;
import com.quaxt.codingagent.agent.ToolInvocation;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.util.AbortSignal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class DebugBreakpointDiagnosticsTest {
    private static final String SUBJECT = "diagnostics.Subject";
    private static final String DRIVER = "diagnostics.Driver";
    private static final String NEVER_LOADED = "diagnostics.NeverLoaded";
    private static final Duration WAIT = Duration.ofSeconds(10);
    @TempDir Path temp;

    @Test
    void nonExecutableLineReportsMatchingClassAndPathWithOrWithoutExplicitClass() throws Exception {
        Fixture fixture = compileFixture("-g");
        exercise(fixture, List.of(
                new Diagnostic(source("explicit", fixture, fixture.nonExecutableLine()).put("class_name", SUBJECT),
                        List.of("No executable location at line", SUBJECT, fixture.sourcePath())),
                new Diagnostic(source("source-only", fixture, fixture.nonExecutableLine()),
                        List.of("No executable location at line", SUBJECT, fixture.sourcePath()))), true);
    }

    @Test
    void loadedExplicitClassWithoutSourceMetadataExplainsMissingSourceInformation() throws Exception {
        Fixture fixture = compileFixture("-g:none");
        exercise(fixture, List.of(new Diagnostic(
                source("no-source", fixture, fixture.executableLine()).put("class_name", SUBJECT),
                List.of("Source debug information is unavailable"))), false);
    }

    @Test
    void loadedExplicitClassWithSourceButNoLineMetadataExplainsMissingLineInformation() throws Exception {
        Fixture fixture = compileFixture("-g:source");
        exercise(fixture, List.of(new Diagnostic(
                source("no-lines", fixture, fixture.executableLine()).put("class_name", SUBJECT),
                List.of("Line-number debug information is unavailable"))), false);
    }

    @Test
    void loadedExplicitClassWithDifferentSourceReportsSourceMismatch() throws Exception {
        Fixture fixture = compileFixture("-g");
        exercise(fixture, List.of(new Diagnostic(
                source("wrong-source", fixture, fixture.executableLine()).put("class_name", SUBJECT)
                        .put("source_path", fixture.unrelatedSourcePath()),
                List.of("does not match"))), false);
    }

    @Test
    void executableLineWithWrongMethodOrSignatureReportsFilterMismatch() throws Exception {
        Fixture fixture = compileFixture("-g");
        exercise(fixture, List.of(
                new Diagnostic(source("wrong-method", fixture, fixture.executableLine())
                        .put("class_name", SUBJECT).put("method_name", "missing").put("signature", "(I)I"),
                        List.of("does not match the requested method/signature")),
                new Diagnostic(source("wrong-signature", fixture, fixture.executableLine())
                        .put("class_name", SUBJECT).put("method_name", "compute").put("signature", "()V"),
                        List.of("does not match the requested method/signature"))), false);
    }

    private void exercise(Fixture fixture, List<Diagnostic> diagnostics, boolean checkUpdate) throws Exception {
        try (Target target = new Target(temp)) {
            target.launch(fixture);
            target.checkpoint("after-subject", "afterSubject");
            target.checkpoint("after-unrelated", "afterUnrelated");
            ObjectNode never = source("never-loaded", fixture, fixture.nonExecutableLine())
                    .put("class_name", NEVER_LOADED);
            String unloadedReason = waitingFor(NEVER_LOADED);
            assertEquals(unloadedReason, pending(target.add(never)));
            for (Diagnostic diagnostic : diagnostics) {
                String reason = pending(target.add(diagnostic.spec()));
                if (diagnostic.spec().has("class_name")) assertEquals(waitingFor(SUBJECT), reason);
            }

            // VM-start is earlier than main-class preparation. These method-entry checkpoints
            // prove Subject has actually loaded before inspecting its metadata diagnostics.
            int beforeLoad = target.eventCursor();
            target.resumeTo("after-subject");
            assertEquals(unloadedReason, target.reason("never-loaded"));
            Map<String, String> expected = new LinkedHashMap<>();
            for (Diagnostic diagnostic : diagnostics) {
                String id = diagnostic.spec().path("breakpoint_id").asText();
                String reason = target.reason(id);
                assertContains(reason, diagnostic.fragments());
                assertNotEquals(waitingFor(SUBJECT), reason);
                target.assertPendingEventSince(beforeLoad, id, reason);
                expected.put(id, reason);

                // Also exercise resolution over all already-loaded classes, not just
                // ClassPrepareEvent: an unrelated class must not replace a useful reason.
                String loadedId = id + "-already-loaded";
                ObjectNode loaded = diagnostic.spec().deepCopy().put("breakpoint_id", loadedId);
                assertEquals(reason, pending(target.add(loaded)));
                expected.put(loadedId, reason);
            }

            target.resumeTo("after-unrelated");
            assertEquals(unloadedReason, target.reason("never-loaded"));
            for (var entry : expected.entrySet()) assertEquals(entry.getValue(), target.reason(entry.getKey()));
            assertEquals("Disabled", pending(target.mutate(obj().put("action", "disable").put("breakpoint_id", "never-loaded"))));
            assertEquals(unloadedReason, pending(target.mutate(obj().put("action", "enable").put("breakpoint_id", "never-loaded"))));
            for (var entry : expected.entrySet()) {
                target.mutate(obj().put("action", "disable").put("breakpoint_id", entry.getKey()));
                assertEquals(entry.getValue(), pending(target.mutate(obj().put("action", "enable").put("breakpoint_id", entry.getKey()))));
            }

            if (checkUpdate) {
                int cursor = target.eventCursor();
                int requestedLine = fixture.nonExecutableLine() + 1000;
                JsonNode changed = target.mutate(obj().put("action", "update")
                        .put("breakpoint_id", "source-only").put("line", requestedLine));
                String reason = pending(changed);
                assertContains(reason, List.of("No executable location at line", Integer.toString(requestedLine),
                        SUBJECT, fixture.sourcePath()));
                assertNotEquals(expected.get("source-only"), reason);
                assertEquals(reason, target.reason("source-only"));
                target.assertPendingEventSince(cursor, "source-only", reason);
            }
        }
    }

    private Fixture compileFixture(String debugOption) throws Exception {
        Path root = temp.resolve("src/test/java/diagnostics");
        Path classes = temp.resolve("classes");
        Files.createDirectories(root);
        Files.createDirectories(classes);
        String subject = """
                package diagnostics;
                public final class Subject {
                    // NON_EXECUTABLE_LOCATION
                    public static int compute(int value) {
                        return value + 1; // EXECUTABLE_LOCATION
                    }
                }
                """;
        Path subjectFile = root.resolve("Subject.java");
        Files.writeString(subjectFile, subject);
        Path driverFile = root.resolve("Driver.java");
        Files.writeString(driverFile, """
                package diagnostics;
                public final class Driver {
                    public static void main(String[] args) throws Exception {
                        Class.forName("diagnostics.Subject");
                        afterSubject();
                        Class.forName("diagnostics.Unrelated");
                        afterUnrelated();
                    }
                    public static void afterSubject() { System.out.println("SUBJECT_LOADED"); }
                    public static void afterUnrelated() { System.out.println("UNRELATED_LOADED"); }
                }
                """);
        Path unrelatedFile = root.resolve("Unrelated.java");
        Files.writeString(unrelatedFile, """
                package diagnostics;
                public final class Unrelated {
                    public static int unrelated() { return 7; }
                }
                """);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK compiler is required for the isolated JDWP fixtures");
        compile(compiler, classes, "-g", driverFile, unrelatedFile);
        compile(compiler, classes, debugOption, subjectFile);
        return new Fixture(classes, "src/test/java/diagnostics/Subject.java",
                "src/test/java/diagnostics/Unrelated.java", line(subject, "NON_EXECUTABLE_LOCATION"),
                line(subject, "// EXECUTABLE_LOCATION"));
    }

    private static void compile(JavaCompiler compiler, Path classes, String debugOption, Path... sources) {
        var arguments = new java.util.ArrayList<String>(List.of("-proc:none", debugOption, "-d", classes.toString()));
        for (Path source : sources) arguments.add(source.toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = compiler.run(null, output, output, arguments.toArray(String[]::new));
        assertEquals(0, exit, output.toString(StandardCharsets.UTF_8));
    }

    private static int line(String source, String marker) {
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(marker)) return i + 1;
        throw new AssertionError("Missing fixture marker: " + marker);
    }

    private static ObjectNode source(String id, Fixture fixture, int line) {
        return obj().put("breakpoint_id", id).put("type", "source")
                .put("source_path", fixture.sourcePath()).put("line", line);
    }

    private static String waitingFor(String className) {
        return "Class " + className + " is not loaded yet; waiting for class preparation";
    }

    private static String pending(JsonNode breakpoint) {
        assertEquals("pending", breakpoint.path("verification").asText(), breakpoint.toString());
        assertEquals(0, breakpoint.path("locations").size(), breakpoint.toString());
        String reason = breakpoint.path("pending_reason").asText();
        assertFalse(reason.isBlank(), breakpoint.toString());
        return reason;
    }

    private static void assertContains(String reason, List<String> fragments) {
        // JDI and host paths may use different separators on Windows.
        String normalized = reason.replace('\\', '/');
        for (String fragment : fragments)
            assertTrue(normalized.contains(fragment.replace('\\', '/')), "Expected " + fragment + " in: " + reason);
    }

    private static ObjectNode obj() { return Json.MAPPER.createObjectNode(); }

    private record Fixture(Path classes, String sourcePath, String unrelatedSourcePath,
                           int nonExecutableLine, int executableLine) {}
    private record Diagnostic(ObjectNode spec, List<String> fragments) {}

    /** Owns exactly one launched session/PID; no process enumeration or application runtime. */
    private static final class Target implements AutoCloseable {
        private final DebugManager manager = new DebugManager();
        private final Map<String, ToolDefinition.Bound<?>> tools = new LinkedHashMap<>();
        private final Path cwd;
        private String session;
        private ProcessHandle process;

        Target(Path cwd) {
            this.cwd = cwd;
            for (AgentTool tool : DebugTools.bind(manager, cwd)) {
                ToolDefinition.Bound<?> bound = (ToolDefinition.Bound<?>) tool;
                tools.put(bound.definition().name(), bound);
            }
        }

        private ObjectNode execute(String operation, ObjectNode arguments) throws Exception {
            AgentTool.ToolResult result = tools.get(operation).execute(
                    new ToolInvocation("diagnostics-" + operation, arguments, new AbortSignal(), ignored -> {}));
            ObjectNode json = (ObjectNode) result.details;
            // Retain ownership even if launch returns an error after creating the child.
            if (operation.equals("debug_launch")) {
                if (json.path("pid").asLong() > 0) process = ProcessHandle.of(json.path("pid").asLong()).orElse(null);
                if (!json.path("session_id").asText().isBlank()) session = json.path("session_id").asText();
            }
            assertFalse(result.isError, operation + ": " + json);
            assertNotEquals("error", json.path("status").asText(), operation + ": " + json);
            return json;
        }

        private ObjectNode call(String operation, ObjectNode arguments) throws Exception {
            return execute(operation, arguments.put("session_id", session));
        }

        void launch(Fixture fixture) throws Exception {
            ObjectNode arguments = obj().put("main_class", DRIVER).put("working_directory", cwd.toString())
                    .put("stop_on_entry", true).put("launch_timeout_ms", 10000);
            arguments.putArray("classpath").add(fixture.classes().toString());
            ObjectNode launched = execute("debug_launch", arguments);
            assertNotNull(session, launched.toString());
            assertNotNull(process, launched.toString());
            assertEquals("launched", launched.path("ownership").asText(), launched.toString());
            ObjectNode entry = awaitStopped(null);
            assertEquals("entry", entry.path("stop").path("reason").asText(), entry.toString());
        }

        void checkpoint(String id, String method) throws Exception {
            mutate(obj().put("action", "add").put("breakpoint_id", id).put("type", "method_entry")
                    .put("class_name", DRIVER).put("method_name", method).put("signature", "()V"));
        }

        JsonNode add(ObjectNode spec) throws Exception {
            return mutate(spec.deepCopy().put("action", "add"));
        }

        JsonNode mutate(ObjectNode arguments) throws Exception {
            ObjectNode response = call("debug_breakpoints", arguments);
            assertEquals(1, response.path("breakpoints").size(), response.toString());
            return response.path("breakpoints").get(0);
        }

        String reason(String id) throws Exception {
            ObjectNode response = call("debug_breakpoints", obj().put("action", "list").put("limit", 100));
            for (JsonNode breakpoint : response.path("breakpoints"))
                if (id.equals(breakpoint.path("breakpoint_id").asText())) return pending(breakpoint);
            throw new AssertionError("Missing breakpoint " + id + ": " + response);
        }

        int eventCursor() throws Exception {
            return call("debug_status", obj()).path("event_cursor").asInt();
        }

        void resumeTo(String checkpoint) throws Exception {
            ObjectNode status = call("debug_status", obj());
            assertEquals("stopped", status.path("status").asText(), status.toString());
            String previousStop = status.path("stop").path("stop_id").asText();
            assertFalse(previousStop.isBlank(), status.toString());
            call("debug_continue", obj().put("stop_id", previousStop));
            ObjectNode stopped = awaitStopped(previousStop);
            assertEquals("method_entry", stopped.path("stop").path("reason").asText(), stopped.toString());
            assertEquals(checkpoint, stopped.path("stop").path("breakpoint_id").asText(), stopped.toString());
        }

        private ObjectNode awaitStopped(String previousStop) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            ObjectNode status = null;
            while (System.nanoTime() < deadline) {
                status = call("debug_status", obj());
                if ("stopped".equals(status.path("status").asText())
                        && !status.path("stop").path("stop_id").asText().equals(previousStop)) return status;
                assertNotEquals("completed", status.path("status").asText(), "Target exited before checkpoint: " + status);
                call("debug_wait", obj().put("cursor", status.path("event_cursor").asInt()).put("wait_ms", 100));
            }
            throw new AssertionError("Timed out waiting for fixture stop: " + status);
        }

        void assertPendingEventSince(int cursor, String id, String reason) throws Exception {
            // Filtered, integer-cursor paging is bounded even if many classes prepare.
            for (int page = 0; page < 20; page++) {
                ObjectNode arguments = obj().put("cursor", cursor).put("limit", 100);
                arguments.putArray("event_types").add("breakpoint_pending");
                ObjectNode response = call("debug_events", arguments);
                assertFalse(response.path("gap").asBoolean(), response.toString());
                for (JsonNode event : response.path("events")) {
                    assertTrue(event.path("cursor").asInt() > cursor, event.toString());
                    JsonNode data = event.path("data");
                    if (id.equals(data.path("breakpoint_id").asText())
                            && reason.equals(data.path("pending_reason").asText())) {
                        assertEquals("pending", data.path("verification").asText(), event.toString());
                        return;
                    }
                }
                if (!response.path("truncated").asBoolean()) break;
                int next = response.path("next_cursor").asInt();
                assertTrue(next > cursor, response.toString());
                cursor = next;
            }
            fail("Missing breakpoint_pending update for " + id + ": " + reason);
        }

        @Override
        public void close() {
            try {
                if (session != null) manager.call("debug_detach", obj().put("session_id", session)
                        .put("leave_running", false).put("terminate", true).put("close_session", true), cwd);
            } finally {
                try { manager.close(); }
                finally {
                    if (process != null && process.isAlive()) {
                        process.destroy();
                        try { process.onExit().get(2, TimeUnit.SECONDS); }
                        catch (Exception ignored) {
                            process.destroyForcibly();
                            try { process.onExit().get(2, TimeUnit.SECONDS); }
                            catch (Exception alsoIgnored) { /* Only our owned fixture PID is eligible. */ }
                        }
                    }
                }
            }
        }
    }
}
