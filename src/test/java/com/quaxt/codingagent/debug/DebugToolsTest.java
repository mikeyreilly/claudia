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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DebugToolsTest {
    private static final Path WORKSPACE = Path.of("").toAbsolutePath().normalize();
    private static final String SOURCE = "src/test/java/com/quaxt/codingagent/debug/DebugFixture.java";
    private static final Duration WAIT = Duration.ofSeconds(10);
    @TempDir Path temp;

    @Test
    void everyCursorSchemaAndParserAcceptsNonnegativeIntegersOnly() throws Exception {
        try (DebugManager manager = new DebugManager()) {
            int cursorTools = 0;
            for (AgentTool tool : DebugTools.bind(manager, WORKSPACE)) {
                ToolDefinition.Bound<?> bound = (ToolDefinition.Bound<?>) tool;
                var parameters = bound.definition().parameters();
                JsonNode schema = parameters.schema();
                JsonNode cursor = schema.path("properties").path("cursor");
                if (cursor.isMissingNode()) continue;
                cursorTools++;
                assertEquals("integer", cursor.path("type").asText(), bound.definition().name());
                assertEquals(0, cursor.path("minimum").asInt());
                assertEquals(Integer.MAX_VALUE, cursor.path("maximum").asInt());
                assertEquals(0, cursor.path("default").asInt());
                ObjectNode arguments = obj();
                for (JsonNode required : schema.path("required"))
                    arguments.put(required.asText(), required.asText().equals("action") ? "list" : "test");
                assertDoesNotThrow(() -> parameters.parse(arguments));
                for (int value : List.of(0, 1, Integer.MAX_VALUE))
                    assertDoesNotThrow(() -> parameters.parse(arguments.deepCopy().put("cursor", value)));
                for (JsonNode invalid : List.<JsonNode>of(Json.MAPPER.valueToTree("0"), Json.MAPPER.valueToTree(""),
                        Json.MAPPER.valueToTree(-1), Json.MAPPER.valueToTree(1.5),
                        Json.MAPPER.valueToTree((long) Integer.MAX_VALUE + 1))) {
                    ObjectNode rejected = arguments.deepCopy();
                    rejected.set("cursor", invalid);
                    assertThrows(IllegalArgumentException.class, () -> parameters.parse(rejected));
                }
                // Execute through the binding too: an unknown session may fail, but cursor validation must not.
                AgentTool.ToolResult result = bound.execute(new ToolInvocation("cursor", arguments.deepCopy().put("cursor", 0),
                        new AbortSignal(), ignored -> {}));
                ObjectNode response = (ObjectNode) result.details;
                assertNotEquals("invalid_argument", response.path("code").asText(), response.toString());
                assertFalse(response.toString().contains("cursor must"), response.toString());
            }
            assertEquals(10, cursorTools, "All paginated debugger operations must share the cursor contract");
        }
    }

    @Test
    void breakpointSchemaIsCompactAndSeparatesAddsFromPartialUpdates() {
        try (Target target = new Target()) {
            JsonNode schema = target.tools.get("debug_breakpoints").definition().parameters().schema();
            assertNoRootCombinators(schema);
            assertEquals(Set.of("session_id", "action", "breakpoints", "breakpoint_ids", "limit", "cursor", "idempotency_key"),
                    Json.MAPPER.convertValue(schema.path("properties"), Map.class).keySet());
            assertEquals(Json.MAPPER.valueToTree(List.of("session_id")), schema.path("required"));
            JsonNode properties = schema.path("properties");
            assertEquals("list", properties.path("action").path("default").asText());
            assertEnum(properties.path("action"), "list", "add", "update", "enable", "disable", "remove");
            assertEquals(1, properties.path("breakpoints").path("minItems").asInt());
            assertEquals(64, properties.path("breakpoints").path("maxItems").asInt());
            assertEquals(1, properties.path("breakpoint_ids").path("minItems").asInt());
            assertEquals(64, properties.path("breakpoint_ids").path("maxItems").asInt());
            JsonNode spec = properties.path("breakpoints").path("items");
            assertTrue(spec.path("required").isEmpty(), "Updates must not require a new location");
            spec.path("properties").forEach(property -> assertFalse(property.has("default"), "Patch fields must not acquire defaults"));
            assertEnum(spec.path("properties").path("type"), "source", "method_entry", "method_exit", "exception", "field");
            assertEnum(spec.path("properties").path("hit_policy"), "after", "exact", "every");
            assertEnum(spec.path("properties").path("suspension_policy"), "all_threads", "event_thread");

            assertRequired(schema.path("if"), "action"); // Missing action must not accidentally select add.
            assertEnum(schema.path("if").path("properties").path("action"), "add");
            assertRequired(schema.path("then"), "breakpoints");
            JsonNode variants = schema.path("then").path("properties").path("breakpoints").path("items").path("anyOf");
            assertEquals(4, variants.size(), "Method entry/exit share location requirements, not an action x type product");
            assertRequired(variants.get(0), "source_path", "line");
            assertEnum(variants.get(0).path("properties").path("type"), "source");
            assertEquals(1, variants.get(0).path("properties").path("line").path("minimum").asInt());
            assertRequired(variants.get(1), "type", "class_name", "method_name");
            assertEnum(variants.get(1).path("properties").path("type"), "method_entry", "method_exit");
            assertRequired(variants.get(2), "type"); // exception_class is an optional filter.
            assertEnum(variants.get(2).path("properties").path("type"), "exception");
            assertRequired(variants.get(3), "type", "class_name", "field_name");
            assertEnum(variants.get(3).path("properties").path("type"), "field");
            JsonNode update = schema.path("else");
            assertEnum(update.path("if").path("properties").path("action"), "update");
            assertRequired(update.path("then"), "breakpoints");
            assertRequired(update.path("then").path("properties").path("breakpoints").path("items"), "breakpoint_id");
            assertEnum(update.path("else").path("if").path("properties").path("action"), "enable", "disable", "remove");
            assertRequired(update.path("else").path("then"), "breakpoint_ids");
        }
    }

    @Test
    void minimalBreakpointBindingsAndLegacyFlattenedCallsReachTheManager() throws Exception {
        try (Target target = new Target()) {
            var parameters = target.tools.get("debug_breakpoints").definition().parameters();
            ObjectNode source = obj().put("session_id", "missing").put("action", "add");
            source.putArray("breakpoints").add(obj().put("source_path", SOURCE).put("line", 1));
            ObjectNode update = obj().put("session_id", "missing").put("action", "update");
            update.putArray("breakpoints").add(obj().put("breakpoint_id", "bp").put("enabled", false));
            ObjectNode legacy = obj().put("session_id", "missing").put("action", "add")
                    .put("source_path", SOURCE).put("line", 1).put("type", "source");
            for (ObjectNode arguments : List.of(source, update, legacy, obj().put("session_id", "missing"),
                    obj().put("session_id", "missing").put("action", "list").put("limit", 1).put("cursor", 0))) {
                assertDoesNotThrow(() -> parameters.parse(arguments));
                assertNotEquals("invalid_argument", target.invoke("debug_breakpoints", arguments).path("code").asText());
            }
            for (String action : List.of("enable", "disable", "remove")) {
                ObjectNode ids = obj().put("session_id", "missing").put("action", action);
                ids.putArray("breakpoint_ids").add("bp");
                assertDoesNotThrow(() -> parameters.parse(ids));
                assertNotEquals("invalid_argument", target.invoke("debug_breakpoints", ids).path("code").asText());
            }
            ObjectNode invalidPatch = update.deepCopy();
            ((ObjectNode) invalidPatch.path("breakpoints").get(0)).put("enabled", "false");
            assertThrows(IllegalArgumentException.class, () -> parameters.parse(invalidPatch));
            // withSchema changes advertising only; hidden flattened parameters still validate their types.
            assertThrows(IllegalArgumentException.class, () -> parameters.parse(legacy.deepCopy().put("line", "1")));
        }
    }

    @Test
    void evaluateAdvertisesExactOneSingleOrBoundedBatchAndPreservesTypedParsing() {
        try (Target target = new Target()) {
            var parameters = target.tools.get("debug_evaluate").definition().parameters();
            JsonNode schema = parameters.schema();
            assertNoRootCombinators(schema);
            assertRequired(schema, "session_id", "stop_id");
            assertRequired(schema.path("if"), "expression");
            assertRequired(schema.path("then").path("not"), "expressions");
            assertRequired(schema.path("else"), "expressions");
            JsonNode properties = schema.path("properties");
            assertEquals(256, properties.path("expression").path("maxLength").asInt());
            assertEquals("array", properties.path("expressions").path("type").asText());
            assertEquals(1, properties.path("expressions").path("minItems").asInt());
            assertEquals(20, properties.path("expressions").path("maxItems").asInt());
            assertEquals("string", properties.path("expressions").path("items").path("type").asText());
            assertEquals(256, properties.path("expressions").path("items").path("maxLength").asInt());
            JsonNode timeout = properties.path("timeout_ms");
            assertEquals(0, timeout.path("minimum").asInt());
            assertEquals(30000, timeout.path("maximum").asInt());
            assertEquals(0, timeout.path("default").asInt());
            assertTrue(timeout.path("description").asText().contains("Does not interrupt in-flight JDI reads"));
            ObjectNode base = obj().put("session_id", "missing").put("stop_id", "stop");
            assertDoesNotThrow(() -> parameters.parse(base.deepCopy().put("expression", "value")));
            assertDoesNotThrow(() -> parameters.parse(base.deepCopy().put("expression", "x".repeat(256))));
            assertDoesNotThrow(() -> parameters.parse(base.deepCopy().put("expression", "\uD83D\uDE00".repeat(256))));
            assertThrows(IllegalArgumentException.class, () -> parameters.parse(base.deepCopy().put("expression", "x".repeat(257))));
            for (int count : List.of(1, 20)) {
                ObjectNode batch = base.deepCopy();
                var expressions = batch.putArray("expressions");
                for (int i = 0; i < count; i++) expressions.add("x".repeat(256));
                assertDoesNotThrow(() -> parameters.parse(batch)); // Size limits/exact-one are enforced by the manager.
            }
            ObjectNode nonString = base.deepCopy();
            nonString.putArray("expressions").add(1);
            assertThrows(IllegalArgumentException.class, () -> parameters.parse(nonString));
            assertThrows(IllegalArgumentException.class, () -> parameters.parse(base.deepCopy().put("expressions", "value")));
            for (int budget : List.of(0, 1, 30000))
                assertDoesNotThrow(() -> parameters.parse(base.deepCopy().put("expression", "value").put("timeout_ms", budget)));
            for (int budget : List.of(-1, 30001))
                assertThrows(IllegalArgumentException.class, () -> parameters.parse(base.deepCopy().put("expression", "value").put("timeout_ms", budget)));
        }
    }

    @Test
    void enumsAndResumeWaitContractsAreAdvertised() {
        try (Target target = new Target()) {
            for (String operation : List.of("debug_continue", "debug_step", "debug_run_to")) {
                var definition = target.tools.get(operation).definition();
                JsonNode wait = definition.parameters().schema().path("properties").path("wait_ms");
                assertEquals(0, wait.path("minimum").asInt());
                assertEquals(30000, wait.path("maximum").asInt());
                assertEquals(1000, wait.path("default").asInt());
                assertTrue(wait.path("description").asText().contains("Expiry leaves the JVM running"));
                assertTrue(definition.description().contains("Wait expiry leaves the JVM running"));
                ObjectNode base = obj().put("session_id", "missing").put("stop_id", "stop");
                for (int milliseconds : List.of(0, 1000, 30000))
                    assertDoesNotThrow(() -> definition.parameters().parse(base.deepCopy().put("wait_ms", milliseconds)));
                for (int milliseconds : List.of(-1, 30001))
                    assertThrows(IllegalArgumentException.class, () -> definition.parameters().parse(base.deepCopy().put("wait_ms", milliseconds)));
            }
            JsonNode direction = target.tools.get("debug_step").definition().parameters().schema().path("properties").path("direction");
            assertEnum(direction, "into", "over", "out");
            assertEquals("over", direction.path("default").asText());
            for (String operation : List.of("debug_wait", "debug_events"))
                assertEnum(target.tools.get(operation).definition().parameters().schema().path("properties").path("event_types").path("items"),
                        "launch", "attach", "detach", "exit", "stop", "resume", "breakpoint_resolved", "breakpoint_pending",
                        "breakpoint_error", "breakpoint_change", "logpoint");
        }
    }

    private static void assertNoRootCombinators(JsonNode schema) {
        for (String combinator : List.of("oneOf", "anyOf", "allOf")) assertFalse(schema.has(combinator));
    }

    private static void assertRequired(JsonNode schema, String... fields) {
        assertEquals(Json.MAPPER.valueToTree(List.of(fields)), schema.path("required"));
    }

    private static void assertEnum(JsonNode schema, String... values) {
        assertEquals(Json.MAPPER.valueToTree(List.of(values)), schema.path("enum"));
    }

    @Test
    @Timeout(45)
    void registeredToolsPageLiveInspectionAndUseStoppedSourceLineForZero() throws Exception {
        Path gate = temp.resolve("tools-go");
        try (Target target = new Target()) {
            target.launch(gate);
            ObjectNode entry = target.awaitState("stopped");
            String entryStop = entry.path("stop").path("stop_id").asText();
            assertEquals(1, target.ok("debug_sessions", obj().put("cursor", 0).put("limit", 1)).path("sessions").size());
            int line = breakpointLine();
            ObjectNode nestedAdd = target.sessionArgs().put("action", "add");
            nestedAdd.putArray("breakpoints").add(obj().put("breakpoint_id", "fixture-line").put("source_path", SOURCE).put("line", line));
            target.ok("debug_breakpoints", nestedAdd);
            target.ok("debug_breakpoints", target.sessionArgs().put("action", "add").put("type", "exception")
                    .put("exception_class", "java.lang.IllegalStateException").put("caught", false));
            ObjectNode firstBreakpoint = target.ok("debug_breakpoints", target.sessionArgs().put("cursor", 0).put("limit", 1));
            assertTrue(firstBreakpoint.path("truncated").asBoolean());
            assertEquals(1, target.ok("debug_breakpoints", target.sessionArgs().put("action", "list")
                    .put("cursor", firstBreakpoint.path("next_cursor").asInt())).path("breakpoints").size());
            target.ok("debug_continue", target.sessionArgs().put("stop_id", entryStop));
            Files.createFile(gate);
            ObjectNode stopped = target.awaitState("stopped");
            assertEquals("fixture-line", stopped.path("stop").path("breakpoint_id").asText(), stopped.toString());
            String stop = stopped.path("stop").path("stop_id").asText();
            ObjectNode base = target.sessionArgs().put("stop_id", stop).put("cursor", 0);

            ObjectNode threads = target.ok("debug_threads", base.deepCopy().put("limit", 1));
            assertEquals(1, threads.path("threads").size(), threads.toString());
            assertTrue(threads.path("truncated").asBoolean(), threads.toString());
            assertTrue(target.ok("debug_threads", base.deepCopy().put("cursor", threads.path("next_cursor").asInt()))
                    .path("threads").size() > 0);
            ObjectNode stack = target.ok("debug_stack", base.deepCopy().put("filter", DebugFixture.class.getName()).put("limit", 1));
            assertEquals("compute", stack.path("frames").get(0).path("method_name").asText());
            assertTrue(stack.path("truncated").asBoolean(), stack.toString());
            assertEquals("main", target.ok("debug_stack", base.deepCopy().put("filter", DebugFixture.class.getName())
                    .put("cursor", stack.path("next_cursor").asInt())).path("frames").get(0).path("method_name").asText());
            String frame = stack.path("frames").get(0).path("frame_id").asText();
            ObjectNode frameArgs = base.deepCopy().put("frame_id", frame);
            ObjectNode vars = target.ok("debug_variables", frameArgs.deepCopy().put("limit", 1));
            assertTrue(vars.path("truncated").asBoolean(), vars.toString());
            assertTrue(target.ok("debug_variables", frameArgs.deepCopy().put("cursor", vars.path("next_cursor").asInt()))
                    .path("variables").size() > 0);
            vars = target.ok("debug_variables", frameArgs.deepCopy());
            String numbers = variable(vars.path("variables"), "numbers").path("reference").asText();
            ObjectNode array = target.ok("debug_object", base.deepCopy().put("reference", numbers).put("limit", 1));
            assertEquals("40", array.path("fields").get(0).path("preview").asText());
            assertTrue(array.path("truncated").asBoolean());
            assertEquals("42", target.ok("debug_object", base.deepCopy().put("reference", numbers)
                    .put("cursor", array.path("next_cursor").asInt())).path("fields").get(0).path("preview").asText());

            ObjectNode sourceArgs = target.sessionArgs().put("stop_id", stop).put("frame_id", frame).put("before", 0).put("after", 0);
            ObjectNode omitted = target.ok("debug_source", sourceArgs.deepCopy());
            ObjectNode zero = target.ok("debug_source", sourceArgs.deepCopy().put("line", 0));
            assertEquals(omitted, zero, "The advertised zero default must use the stopped frame's line");
            assertEquals(line, zero.path("line").asInt());
            assertTrue(zero.path("lines").get(0).path("text").asText().contains("DEBUG_FIXTURE_BREAKPOINT"));
            ObjectNode positive = target.ok("debug_source", sourceArgs.deepCopy().put("line", 1));
            assertEquals(1, positive.path("line").asInt());
            assertTrue(positive.path("lines").get(0).path("text").asText().startsWith("package "));
            int outsideFile = Files.readAllLines(WORKSPACE.resolve(SOURCE)).size() + 1;
            assertEquals("source_mismatch", target.invoke("debug_source", sourceArgs.deepCopy().put("line", outsideFile))
                    .path("code").asText());
            assertThrows(IllegalArgumentException.class, () -> target.invoke("debug_source", sourceArgs.deepCopy().put("line", -1)));
            assertEquals(stop, target.ok("debug_wait", target.sessionArgs().put("cursor", stopped.path("event_cursor").asInt())
                    .put("wait_ms", 0)).path("stop").path("stop_id").asText());

            ObjectNode events = target.ok("debug_events", target.sessionArgs().put("cursor", 0).put("limit", 1));
            assertTrue(events.path("truncated").asBoolean(), events.toString());
            assertTrue(target.ok("debug_events", target.sessionArgs().put("cursor", events.path("next_cursor").asInt()))
                    .path("events").size() > 0);
            target.awaitOutput("DEBUG_FIXTURE_READY");
            target.ok("debug_continue", target.sessionArgs().put("stop_id", stop));
            ObjectNode thrown = target.awaitState("stopped");
            assertEquals("exception", thrown.path("stop").path("reason").asText(), thrown.toString());
            String exceptionStop = thrown.path("stop").path("stop_id").asText();
            ObjectNode exceptionArgs = target.sessionArgs().put("stop_id", exceptionStop).put("cursor", 0);
            ObjectNode exception = target.ok("debug_exception", exceptionArgs);
            assertEquals("fixture failure", exception.path("message").asText());
            assertTrue(exception.path("stack").size() > 0);
            assertEquals(0, target.ok("debug_exception", exceptionArgs.deepCopy().put("cursor", Integer.MAX_VALUE))
                    .path("stack").size());
            target.ok("debug_continue", target.sessionArgs().put("stop_id", exceptionStop));
            target.awaitState("completed");
            target.awaitOutput("DEBUG_FIXTURE_DONE=42");
            ObjectNode output = target.ok("debug_output", target.sessionArgs().put("cursor", 0).put("limit", 1));
            assertTrue(output.path("truncated").asBoolean(), output.toString());
            assertTrue(target.ok("debug_output", target.sessionArgs().put("cursor", output.path("next_cursor").asInt()))
                    .path("output").size() > 0);
        }
    }

    private static ObjectNode obj() { return Json.MAPPER.createObjectNode(); }

    private static int breakpointLine() throws Exception {
        List<String> lines = Files.readAllLines(WORKSPACE.resolve(SOURCE));
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("// DEBUG_FIXTURE_BREAKPOINT")) return i + 1;
        throw new AssertionError("Missing fixture breakpoint");
    }

    private static JsonNode variable(JsonNode variables, String name) {
        for (JsonNode variable : variables) if (name.equals(variable.path("name").asText())) return variable;
        throw new AssertionError("Missing " + name + ": " + variables);
    }

    private static final class Target implements AutoCloseable {
        final DebugManager manager = new DebugManager();
        final Map<String, ToolDefinition.Bound<?>> tools = new LinkedHashMap<>();
        String session;
        ProcessHandle process;

        Target() {
            for (AgentTool tool : DebugTools.bind(manager, WORKSPACE)) {
                ToolDefinition.Bound<?> bound = (ToolDefinition.Bound<?>) tool;
                tools.put(bound.definition().name(), bound);
            }
        }

        ObjectNode invoke(String operation, ObjectNode args) throws Exception {
            AgentTool.ToolResult result = tools.get(operation).execute(
                    new ToolInvocation("tools-" + operation, args, new AbortSignal(), ignored -> {}));
            ObjectNode json = (ObjectNode) result.details;
            assertEquals("error".equals(json.path("status").asText()), result.isError);
            return json;
        }

        ObjectNode ok(String operation, ObjectNode args) throws Exception {
            ObjectNode json = invoke(operation, args);
            assertNotEquals("error", json.path("status").asText(), operation + ": " + json);
            return json;
        }

        ObjectNode sessionArgs() { return obj().put("session_id", session); }

        void launch(Path gate) throws Exception {
            ObjectNode args = obj().put("main_class", DebugFixture.class.getName()).put("stop_on_entry", true);
            args.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
            args.putArray("arguments").add(gate.toString()).add("throw");
            ObjectNode started = ok("debug_launch", args);
            session = started.path("session_id").asText();
            process = ProcessHandle.of(started.path("pid").asLong()).orElseThrow();
        }

        ObjectNode awaitState(String state) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            ObjectNode status = null;
            while (System.nanoTime() < deadline) {
                status = ok("debug_status", sessionArgs());
                if (state.equals(status.path("status").asText())) return status;
                assertNotEquals("completed", status.path("status").asText(), "Target exited before " + state + ": " + status);
                ok("debug_wait", sessionArgs().put("cursor", status.path("event_cursor").asInt()).put("wait_ms", 100));
            }
            throw new AssertionError("Timed out waiting for " + state + ": " + status);
        }

        void awaitOutput(String text) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            int cursor = 0;
            StringBuilder seen = new StringBuilder();
            while (System.nanoTime() < deadline) {
                ObjectNode output = ok("debug_output", sessionArgs().put("cursor", cursor));
                for (JsonNode item : output.path("output")) {
                    seen.append(item.path("text").asText());
                    cursor = item.path("cursor").asInt();
                }
                if (seen.toString().contains(text)) return;
                Thread.sleep(25);
            }
            throw new AssertionError("Missing fixture output: " + text + "; received " + seen);
        }

        @Override public void close() {
            try {
                if (session != null) manager.call("debug_detach", sessionArgs().put("leave_running", false)
                        .put("terminate", true).put("close_session", true), WORKSPACE);
            } finally {
                manager.close();
                if (process != null && process.isAlive()) {
                    process.destroy();
                    try { process.onExit().get(2, TimeUnit.SECONDS); }
                    catch (Exception ignored) { process.destroyForcibly(); }
                }
            }
        }
    }
}
