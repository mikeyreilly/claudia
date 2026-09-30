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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ReadOnlyExpressionTest {
    private static final Path WORKSPACE = Path.of("").toAbsolutePath().normalize();
    private static final String SOURCE = "src/test/java/com/quaxt/codingagent/debug/ReadOnlyExpressionFixture.java";

    @Test
    void validatesOnlyBoundedReadOnlySyntaxBeforeAccessingATarget() {
        for (String valid : List.of("numbers[0]", "matrix[indices[0]][index]", "this.numbers.length",
                "items[index].name == \"beta\"", "\"x==y\" == \"x==y\"", "\"\"", "-2.5 < 1",
                "numbers [ index ] >= 40", "null", "true", "field$with_unicodeΩ[0]"))
            assertDoesNotThrow(() -> ReadOnlyExpression.validate(valid), valid);
        for (String unsafe : List.of("", "numbers[index++]", "numbers[--index]", "numbers[0] = 7",
                "numbers[0] += 1", "sample.count = 99", "this.touch()", "numbers[touch()]", "new Object()",
                "System.exit(0)", "matrix[0][0] + 1", "(numbers[0])", "flag && true", "true == false == true",
                "numbers[0", "numbers[]", "numbers[0]]", "numbers[0].", "\"unterminated", "\"bad\\q\"",
                "\"raw\nnewline\"", "\"bad\\u00XX\"", "\"bad\\u0\"", "-", "1.")) {
            var failure = assertThrows(ReadOnlyExpression.Failure.class, () -> ReadOnlyExpression.validate(unsafe), unsafe);
            assertEquals("unsafe_expression", failure.code, unsafe);
        }
        assertThrows(ReadOnlyExpression.Failure.class, () -> ReadOnlyExpression.validate("a".repeat(257)));
        String deeplyNested = "a[".repeat(18) + "0" + "]".repeat(18);
        assertThrows(ReadOnlyExpression.Failure.class, () -> ReadOnlyExpression.validate(deeplyNested));
    }

    @Test
    @Timeout(300)
    void readsArraysLengthsAndChainedFieldsWithoutCallsOrMutation() throws Exception {
        try (Target target = new Target()) {
            target.stopAtInspection();
            for (var example : Map.ofEntries(
                    Map.entry("numbers[0]", "40"), Map.entry("numbers[index]", "42"),
                    Map.entry("numbers[byteIndex]", "40"), Map.entry("numbers[shortIndex]", "42"),
                    Map.entry("numbers[charIndex]", "40"), Map.entry("numbers.length", "2"),
                    Map.entry("this.numbers[index]", "42"), Map.entry("this.numbers.length", "2"),
                    Map.entry("matrix[indices[0]][index]", "4"), Map.entry("matrix[0].length", "2"),
                    Map.entry("items[index].count", "42"), Map.entry("nestedItems[index][0].name", "beta"),
                    Map.entry("numbers [ index ] >= 40", "true"), Map.entry("letter == 65", "true"),
                    Map.entry("alias == items[0]", "true"), Map.entry("items[0] != items[1]", "true"),
                    Map.entry("nullable == null", "true"), Map.entry("flag == true", "true"),
                    Map.entry("null", "null"), Map.entry("-2.5 < 1", "true"),
                    Map.entry("2147483648", "2147483648")).entrySet()) {
                String expression = example.getKey();
                String expected = example.getValue();
                ObjectNode result = target.evaluate(expression);
                String actual = result.path("value").path("preview").asText();
                assertEquals("ok", result.path("status").asText(), result.toString()); // READ_ONLY_RESULT_INSPECT
                assertEquals("read_only", result.path("safety_level").asText(), result.toString());
                assertEquals(expected, actual, expression + ": " + result);
            }
            assertValue(target, "this.calls", "0");
            assertValue(target, "numbers[0]", "40");
            target.continueToExit();
        }
    }

    @Test
    void readsEscapedStringLiteralsAndComparesStringsByContent() throws Exception {
        try (Target target = new Target()) {
            target.stopAtInspection();
            for (var example : Map.ofEntries(
                    Map.entry("\"OpenAI\"", "OpenAI"), Map.entry("\"\"", ""),
                    Map.entry("this.label == \"ready==go\"", "true"), Map.entry("first == second", "true"),
                    Map.entry("first != \"different\"", "true"), Map.entry("\"ready==go\" == first", "true"),
                    Map.entry("\"ready==go\" != null", "true"), Map.entry("\"alpha\" == items[0].name", "true"),
                    Map.entry("items[index].name == \"beta\"", "true"), Map.entry("\"beta\" == alias", "false"),
                    Map.entry("\"x > y == z\" == \"x > y == z\"", "true"),
                    Map.entry("\"quote\\\" slash\\\\ line\\n tab\\t Ω\" == escaped", "true"),
                    Map.entry("\"\\u03A9\"", "Ω"), Map.entry("\"\\uD83D\\uDE00\"", "😀"),
                    Map.entry("\"\\b\\f\\r\"", "\b\f\r")).entrySet())
                assertValue(target, example.getKey(), example.getValue());
            ObjectNode literal = target.evaluate("\"OpenAI\"");
            assertEquals("java.lang.String", literal.path("value").path("type").asText());
            assertFalse(literal.path("value").has("reference"), "Literal strings must not allocate target objects");
            assertValue(target, "this.calls", "0");
        }
    }

    @Test
    void rejectsInvalidReadsAndUnsafeSyntaxWithoutResumingOrChangingTheTarget() throws Exception {
        try (Target target = new Target()) {
            target.stopAtInspection();
            String stop = target.stop;
            for (var example : Map.ofEntries(
                    Map.entry("numbers[-1]", "index_out_of_bounds"), Map.entry("numbers[2]", "index_out_of_bounds"),
                    Map.entry("numbers[2147483647]", "index_out_of_bounds"),
                    Map.entry("numbers[fractionalIndex]", "invalid_expression"),
                    Map.entry("numbers[longIndex]", "invalid_expression"), Map.entry("numbers[true]", "invalid_expression"),
                    Map.entry("numbers[null]", "invalid_expression"), Map.entry("numbers[\"0\"]", "invalid_expression"),
                    Map.entry("index[0]", "invalid_expression"), Map.entry("nullable.count", "null_reference"),
                    Map.entry("nullArray[0]", "null_reference"), Map.entry("nullArray.length", "null_reference"),
                    Map.entry("numbers[index].count", "invalid_expression"), Map.entry("items[0].missing", "field_not_found"),
                    Map.entry("missing[0]", "variable_unavailable"), Map.entry("first < second", "invalid_expression"),
                    Map.entry("flag == 1", "invalid_expression"), Map.entry("\"text\" == 1", "invalid_expression"),
                    Map.entry("nan == 1", "invalid_expression"), Map.entry("infinity > 1", "invalid_expression"),
                    Map.entry("9223372036854775808", "invalid_expression"),
                    Map.entry("this.touch()", "unsafe_expression"), Map.entry("numbers[index++]", "unsafe_expression"),
                    Map.entry("numbers[0] = 99", "unsafe_expression"), Map.entry("numbers[this.touch()]", "unsafe_expression"),
                    Map.entry("unknown[this.touch()]", "unsafe_expression")).entrySet()) {
                ObjectNode result = target.evaluate(example.getKey());
                assertEquals("error", result.path("status").asText(), result.toString());
                assertEquals(example.getValue(), result.path("code").asText(), example.getKey() + ": " + result);
            }
            ObjectNode bounds = target.evaluate("numbers[2]");
            assertTrue(bounds.path("message").asText().contains("index 2"));
            assertTrue(bounds.path("message").asText().contains("length 2"));
            assertEquals("unsupported", target.call("debug_evaluate", target.base().put("expression", "numbers[0]")
                    .put("allow_side_effects", true)).path("code").asText());
            assertEquals(stop, target.ok("debug_status", target.sessionArgs()).path("stop").path("stop_id").asText());
            assertValue(target, "this.calls", "0");
            assertValue(target, "index", "1");
            assertValue(target, "numbers[0]", "40");
            assertValue(target, "numbers[1]", "42");
            target.continueToExit();
            assertEquals("stale_stop", target.evaluate("\"literal\"").path("code").asText());
        }
    }

    @Test
    void arrayAndStringExpressionsAlsoWorkInConditionsAndLogpoints() throws Exception {
        try (Target target = new Target()) {
            target.launch();
            target.breakpoint("false-condition", "READ_ONLY_EXPRESSION_STOP", "numbers[0] == 41", null);
            target.breakpoint("array-log", "READ_ONLY_EXPRESSION_STOP", "items[index].name == \"beta\"", "matrix[indices[0]][index]");
            target.breakpoint("literal-log", "READ_ONLY_EXPRESSION_STOP", null, "\"ready==go\"");
            target.breakpoint("after", "READ_ONLY_EXPRESSION_AFTER", "numbers.length == 2", null);
            ObjectNode rejected = target.call("debug_breakpoints", target.sessionArgs().put("action", "add")
                    .put("type", "source").put("source_path", SOURCE).put("line", line("READ_ONLY_EXPRESSION_STOP"))
                    .put("condition", "numbers[this.touch()] == 99"));
            assertEquals("unsafe_expression", rejected.path("code").asText());
            target.resume();
            ObjectNode stopped = target.await("stopped");
            assertEquals("after", stopped.path("stop").path("breakpoint_id").asText(), stopped.toString());
            ObjectNode events = target.ok("debug_events", target.sessionArgs().put("cursor", 0).put("limit", 100));
            Map<String, String> logs = new LinkedHashMap<>();
            for (JsonNode event : events.path("events")) {
                assertNotEquals("breakpoint_error", event.path("type").asText(), event.toString());
                if (event.path("type").asText().equals("logpoint")) {
                    JsonNode data = event.path("data");
                    assertFalse(data.has("error"), data.toString());
                    logs.put(data.path("breakpoint_id").asText(), data.path("value").path("preview").asText());
                }
            }
            assertEquals(Map.of("array-log", "4", "literal-log", "ready==go"), logs);
            assertValue(target, "this.calls", "0");
        }
    }

    private static void assertValue(Target target, String expression, String expected) throws Exception {
        ObjectNode result = target.evaluate(expression);
        assertEquals("ok", result.path("status").asText(), result.toString());
        assertEquals(expected, result.path("value").path("preview").asText(), expression + ": " + result);
    }

    private static int line(String marker) throws Exception {
        List<String> lines = Files.readAllLines(WORKSPACE.resolve(SOURCE));
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("// " + marker)) return i + 1;
        throw new AssertionError("Missing fixture marker " + marker);
    }

    private static ObjectNode obj() { return Json.MAPPER.createObjectNode(); }

    private static final class Target implements AutoCloseable {
        private final DebugManager manager = new DebugManager();
        private final Map<String, ToolDefinition.Bound<?>> tools = new LinkedHashMap<>();
        private String session, stop;
        private ProcessHandle process;

        Target() {
            for (AgentTool tool : DebugTools.bind(manager, WORKSPACE)) {
                ToolDefinition.Bound<?> bound = (ToolDefinition.Bound<?>) tool;
                tools.put(bound.definition().name(), bound);
            }
        }

        ObjectNode call(String name, ObjectNode arguments) throws Exception {
            AgentTool.ToolResult result = tools.get(name).execute(
                    new ToolInvocation("expression-" + name, arguments, new AbortSignal(), ignored -> {}));
            ObjectNode json = (ObjectNode) result.details;
            if (name.equals("debug_launch")) {
                if (!json.path("session_id").asText().isBlank()) session = json.path("session_id").asText();
                if (json.path("pid").asLong() > 0) process = ProcessHandle.of(json.path("pid").asLong()).orElse(null);
            }
            assertEquals("error".equals(json.path("status").asText()), result.isError);
            return json;
        }

        ObjectNode ok(String name, ObjectNode arguments) throws Exception {
            ObjectNode json = call(name, arguments);
            assertNotEquals("error", json.path("status").asText(), name + ": " + json);
            return json;
        }

        ObjectNode sessionArgs() { return obj().put("session_id", session); }
        ObjectNode base() { return sessionArgs().put("stop_id", stop); }
        ObjectNode evaluate(String expression) throws Exception {
            return call("debug_evaluate", base().put("expression", expression));
        }

        void launch() throws Exception {
            ObjectNode args = obj().put("main_class", ReadOnlyExpressionFixture.class.getName()).put("stop_on_entry", true);
            args.putArray("classpath").add(WORKSPACE.resolve("target/test-classes").toString());
            ok("debug_launch", args);
            await("stopped");
        }

        void breakpoint(String id, String marker, String condition, String log) throws Exception {
            ObjectNode args = sessionArgs().put("action", "add").put("type", "source")
                    .put("breakpoint_id", id).put("source_path", SOURCE).put("line", line(marker))
                    .put("class_name", ReadOnlyExpressionFixture.class.getName());
            if (condition != null) args.put("condition", condition);
            if (log != null) args.put("log_expression", log);
            ok("debug_breakpoints", args);
        }

        void stopAtInspection() throws Exception {
            launch();
            breakpoint("inspect", "READ_ONLY_EXPRESSION_STOP", null, null);
            resume();
            ObjectNode status = await("stopped");
            assertEquals("inspect", status.path("stop").path("breakpoint_id").asText(), status.toString());
        }

        void resume() throws Exception { ok("debug_continue", base()); }
        void continueToExit() throws Exception { resume(); await("completed"); }

        ObjectNode await(String desired) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            ObjectNode status = null;
            while (System.nanoTime() < deadline) {
                status = ok("debug_status", sessionArgs());
                if (status.path("status").asText().equals(desired)) {
                    if (desired.equals("stopped")) stop = status.path("stop").path("stop_id").asText();
                    return status;
                }
                assertNotEquals("completed", status.path("status").asText(), "Target exited before " + desired + ": " + status);
                ok("debug_wait", sessionArgs().put("cursor", status.path("event_cursor").asInt()).put("wait_ms", 100));
            }
            throw new AssertionError("Timed out waiting for " + desired + ": " + status);
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
