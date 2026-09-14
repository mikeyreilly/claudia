package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.ToolDefinition;
import com.quaxt.codingagent.agent.ToolParameters;
import com.quaxt.codingagent.agent.ToolRegistry;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static com.quaxt.codingagent.agent.ToolParameters.*;
import static org.junit.jupiter.api.Assertions.*;

/** Registration and argument contracts shared by every built-in tool. */
class ToolRegistryTest {
    @TempDir Path cwd;

    @Test
    void aNewDefinitionSuppliesMetadataValidationExecutionAndPresentation() throws Exception {
        var amount = integer("amount", "Amount to add", 0, 10, 0);
        var definition = new ToolDefinition<AtomicInteger>("add", "Add to the bound counter", new ToolParameters(amount),
                (counter, args, call) -> CodingAgentOperations.toolResultText(Integer.toString(counter.addAndGet(args.get(amount)))),
                raw -> "Adding " + raw.path("amount").asInt());
        var registry = new ToolRegistry<>(List.of(definition));
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        var firstTool = registry.bind(first).getFirst();
        var secondTool = registry.bind(second).getFirst();
        try (var runtime = new CodingAgentOperations()) {
            assertEquals("add", CodingAgentOperations.toolName(firstTool));
            assertEquals("Add to the bound counter", CodingAgentOperations.toolDescription(firstTool));
            assertEquals("Adding 3", registry.describeCall("add", jsonObject().put("amount", 3)).orElseThrow());
            assertEquals("3", text(execute(runtime, firstTool, jsonObject().put("amount", 3))));
            assertEquals("0", text(execute(runtime, secondTool, jsonObject())));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, firstTool, jsonObject().put("amount", -1)));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, firstTool, jsonObject().put("amount", 1.5)));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, firstTool, jsonObject().put("unknown", 3)));
            assertEquals(3, first.get(), "invalid calls must not reach the handler");
        }
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry<>(List.of(definition, definition)));
    }

    @Test
    void advertisedBoundsAndValidationComeFromTheSameParameter() {
        var wait = integer("yield_ms", "Wait", 0, 30000, 1000);
        var parameters = new ToolParameters(wait);
        ObjectNode schema = parameters.schema();
        assertEquals(0, schema.path("properties").path("yield_ms").path("minimum").asInt());
        assertEquals(30000, schema.path("properties").path("yield_ms").path("maximum").asInt());
        assertEquals(1000, parameters.parse(jsonObject()).get(wait));
        assertEquals(0, parameters.parse(jsonObject().put("yield_ms", 0)).get(wait));
        assertEquals(30000, parameters.parse(jsonObject().put("yield_ms", 30000)).get(wait));
        assertThrows(IllegalArgumentException.class, () -> parameters.parse(jsonObject().put("yield_ms", 30001)));
        assertThrows(IllegalArgumentException.class, () -> parameters.parse(jsonObject().put("yield_ms", "1000")));
        ((ObjectNode) schema.path("properties").path("yield_ms")).put("minimum", 99);
        assertEquals(0, parameters.schema().path("properties").path("yield_ms").path("minimum").asInt());
    }

    @Test
    void textLengthConstraintsCountUnicodeCharactersAsTheSchemaDoes() {
        var input = optionalText("input", "Short input", null, 2);
        var parameters = new ToolParameters(input);
        String pair = "\uD83D\uDE00\uD83D\uDE00";
        assertEquals(2, parameters.schema().path("properties").path("input").path("maxLength").asInt());
        assertEquals(pair, parameters.parse(jsonObject().put("input", pair)).get(input));
        assertThrows(IllegalArgumentException.class, () -> parameters.parse(jsonObject().put("input", pair + "x")));
    }

    @Test
    void validatesNestedEditsBeforeChangingTheFileAndAllowsEmptyContent() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            var tools = runtime.builtInTools(cwd, ignored -> {});
            execute(runtime, tool(tools, "write"), jsonObject().put("path", "file.txt").put("content", "before"));
            ObjectNode edit = jsonObject().put("path", "file.txt");
            edit.putArray("edits").addObject().put("oldText", "before").put("newText", "after");
            edit.withArray("edits").addObject().put("oldText", "after");
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, tool(tools, "edit"), edit));
            assertEquals("before", Files.readString(cwd.resolve("file.txt")));
            edit.withArray("edits").remove(1);
            ((ObjectNode) edit.withArray("edits").get(0)).put("newText", "");
            execute(runtime, tool(tools, "edit"), edit);
            assertEquals("", Files.readString(cwd.resolve("file.txt")));
            execute(runtime, tool(tools, "write"), jsonObject().put("path", "file.txt").put("content", ""));
            assertEquals("", Files.readString(cwd.resolve("file.txt")));
        }
    }

    @Test
    void builtinSchemasSpecifyZeroYieldAndPositiveReadOffsets() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            var tools = runtime.builtInTools(cwd, ignored -> {});
            for (String name : List.of("shell", "shell_input")) {
                var bounds = CodingAgentOperations.toolParameters(tool(tools, name)).path("properties").path("yield_ms");
                assertEquals(0, bounds.path("minimum").asInt());
                assertEquals(30000, bounds.path("maximum").asInt());
            }
            assertEquals(1, CodingAgentOperations.toolParameters(tool(tools, "read")).path("properties").path("offset").path("minimum").asInt());
            Files.writeString(cwd.resolve("lines.txt"), "one\ntwo\nthree\n");
            assertEquals("two\nthree", text(execute(runtime, tool(tools, "read"), jsonObject().put("path", "lines.txt").put("offset", 2))));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, tool(tools, "read"), jsonObject().put("path", "lines.txt").put("offset", 0)));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, tool(tools, "read"), jsonObject().put("path", "lines.txt").put("offset", 1.5)));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, tool(tools, "shell"), jsonObject().put("command", "echo unreachable").put("yield_ms", -1)));
            assertThrows(IllegalArgumentException.class, () -> execute(runtime, tool(tools, "shell"), jsonObject().put("command", "echo unreachable").put("timeout", 0)));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shellDescriptionAdvertisesTheVersionOfTheInterpreterItRuns() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            var shell = tool(runtime.builtInTools(cwd, ignored -> {}), "shell");
            var result = execute(runtime, shell, jsonObject()
                    .put("command", "$PSVersionTable.PSVersion.ToString()").put("yield_ms", 5000));
            assertFalse(result.isError, text(result));
            assertTrue(text(result).endsWith("[Command exited with code 0.]"), text(result));
            String version = text(result).lines().findFirst().orElseThrow().strip();
            assertTrue(version.matches("[0-9]+(?:\\.[0-9]+){1,3}"), version);
            String description = CodingAgentOperations.toolDescription(shell);
            assertTrue(description.startsWith("Execute a PowerShell " + version + " (powershell.exe) command"), description);
        }
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(candidate -> CodingAgentOperations.toolName(candidate).equals(name)).findFirst().orElseThrow();
    }

    private static AgentTool.ToolResult execute(CodingAgentOperations runtime, AgentTool tool, ObjectNode arguments) throws Exception {
        return runtime.executeTool(tool, "test", arguments, new AbortSignal(), ignored -> {});
    }

    private static String text(AgentTool.ToolResult result) {
        return ((TextContent) result.content.getFirst()).text;
    }
}
