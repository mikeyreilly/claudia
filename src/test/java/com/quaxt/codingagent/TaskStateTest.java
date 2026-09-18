package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentMode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.util.AbortSignal;
import com.quaxt.codingagent.cli.tools.TaskState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;

class TaskStateTest {
    @TempDir Path workspace;

    private static AgentTool tool(CodingAgentOperations runtime, Path cwd) {
        return runtime.builtInTools(cwd, ignored -> {}).stream()
                .filter(candidate -> CodingAgentOperations.toolName(candidate).equals("task_state"))
                .findFirst().orElseThrow();
    }

    private static String call(CodingAgentOperations runtime, AgentTool tool, ObjectNode arguments) throws Exception {
        AgentTool.ToolResult result = runtime.executeTool(tool, "task-test", arguments, new AbortSignal(), ignored -> {});
        assertFalse(result.isError);
        return ((TextContent) result.content.getFirst()).text;
    }

    private static ObjectNode action(String name) { return jsonObject().put("action", name); }

    @Test void tasksDependenciesValidationAndStableIds() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            AgentTool tool = tool(runtime, workspace);
            assertTrue(CodingAgentOperations.toolDescription(tool).contains("compaction"));
            assertEquals("action", CodingAgentOperations.toolParameters(tool).path("required").get(0).asText());
            assertTrue(call(runtime, tool, action("add_task").put("description", "Inspect repository"))
                    .contains("#1 [todo] Inspect repository"));
            ObjectNode second = action("add_task").put("description", "Implement change");
            second.putArray("depends_on").add("#1");
            call(runtime, tool, second);
            String updated = call(runtime, tool, action("update_task").put("id", "#2")
                    .put("status", "in_progress").put("note", "Investigating"));
            assertTrue(updated.contains("#2 [in_progress] Implement change | depends_on: #1 | note: Investigating"));
            ObjectNode clear = action("update_task").put("id", "#2").put("description", "Finish change").put("note", "");
            clear.putArray("depends_on");
            call(runtime, tool, clear);
            assertFalse(call(runtime, tool, action("list")).contains("Investigating"));
            // Restore #2's dependency to exercise a cycle.
            ObjectNode dependency = action("update_task").put("id", "#2");
            dependency.putArray("depends_on").add("#1");
            call(runtime, tool, dependency);
            ObjectNode cycle = action("update_task").put("id", "#1");
            cycle.putArray("depends_on").add("#2");
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool, cycle));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool,
                    action("update_task").put("id", "#2").put("status", "unknown")));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool,
                    action("update_task").put("id", "#3").put("note", "bad")));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool,
                    action("update_task").put("id", "#02").put("note", "bad")));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool,
                    action("add_task").put("description", " ")));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool, action("invalid")));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool, jsonObject()));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool, action("list").put("note", "extra")));
            ObjectNode unknownDependency = action("add_task").put("description", "Bad");
            unknownDependency.putArray("depends_on").add("#99");
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool, unknownDependency));
            call(runtime, tool, action("update_task").put("id", "#1").put("status", "done"));
            call(runtime, tool, action("update_task").put("id", "#2").put("status", "cancelled")
                    .put("note", "Requirement withdrawn"));
            call(runtime, tool, action("remove_task").put("id", "#1"));
            String listed = call(runtime, tool, action("list"));
            assertFalse(listed.contains("depends_on: #1"));
            assertTrue(listed.contains("#2 [cancelled] Finish change | note: Requirement withdrawn"));
            assertTrue(call(runtime, tool, action("add_task").put("description", "Follow-up"))
                    .contains("#3 [todo] Follow-up"));
        }
    }

    @Test void findingsConstraintsAndJournalRollback() throws Exception {
        try (var runtime = new CodingAgentOperations()) {
            runtime.sessionStore(workspace.resolve("sessions"), List.of());
            runtime.createSessionRecorder(workspace, "faux", "faux-1");
            runtime.setSessionRecording(true, error -> fail(error));
            AgentTool tool = tool(runtime, workspace);
            call(runtime, tool, action("add_finding").put("text", "The API is synchronous"));
            call(runtime, tool, action("add_constraint").put("text", "Preserve old sessions"));
            assertThrows(IllegalArgumentException.class, () -> call(runtime, tool, action("remove_finding").put("id", "C1")));
            call(runtime, tool, action("remove_finding").put("id", "F1"));
            call(runtime, tool, action("remove_constraint").put("id", "C1"));
            assertTrue(call(runtime, tool, action("add_finding").put("text", "Second finding")).contains("F2 Second finding"));
            assertTrue(call(runtime, tool, action("add_constraint").put("text", "Second constraint")).contains("C2 Second constraint"));
            String id = runtime.state().sessionId();
            assertEquals("task_state", runtime.readSession(id).getLast().type);
            String before = call(runtime, tool, action("list"));
            Files.delete(workspace.resolve("sessions").resolve(id + ".jsonl"));
            assertThrows(IOException.class, () -> call(runtime, tool, action("add_task").put("description", "Unwritten")));
            assertEquals(before, call(runtime, tool, action("list")));
        }
    }

    @Test void compactionResumeLegacyAndFreshSessionLifetime() throws Exception {
        Model model = model();
        var provider = new FauxProvider("faux", "faux", List.of(model));
        String id;
        try (var runtime = new CodingAgentOperations()) {
            runtime.applicationPaths(new CodingAgentPaths(workspace.resolve("home")));
            runtime.configureAgent(provider, model, workspace, "Project context", null, ThinkingLevel.OFF);
            runtime.sessionStore(workspace.resolve("sessions"), List.of());
            runtime.createSessionRecorder(workspace, "faux", "faux-1");
            runtime.setSessionRecording(true, error -> fail(error));
            id = runtime.state().sessionId();
            call(runtime, tool(runtime, workspace), action("add_task").put("description", "Survive compaction"));
            runtime.restoreMessages(List.of(CodingAgentOperations.userMessage("Lots of context")));
            provider.pendingResponses.add(new FauxProvider.ResponseStep.Factory(request -> SubagentManagerTest.answer("Summary")));
            runtime.compact(null);
            assertTrue(call(runtime, tool(runtime, workspace), action("list")).contains("Survive compaction"));
            assertEquals("#1", runtime.sessionSnapshot(id).taskState.path("tasks").get(0).path("id").asText());
        }
        try (var resumed = new CodingAgentOperations()) {
            resumed.sessionStore(workspace.resolve("sessions"), List.of());
            resumed.resumeSessionRecorder(id);
            assertTrue(call(resumed, tool(resumed, workspace), action("list")).contains("#1 [todo] Survive compaction"));
            resumed.createSessionRecorder(workspace, "faux", "faux-1");
            assertTrue(call(resumed, tool(resumed, workspace), action("list")).contains("Tasks: (none)"));
            assertNull(resumed.sessionSnapshot(resumed.state().sessionId()).taskState);
        }
        try (var legacy = new CodingAgentOperations()) {
            legacy.sessionStore(workspace.resolve("legacy"), List.of());
            legacy.createSessionRecorder(workspace, "faux", "faux-1");
            String legacyId = legacy.state().sessionId();
            legacy.resumeSessionRecorder(legacyId);
            assertTrue(call(legacy, tool(legacy, workspace), action("list")).contains("Tasks: (none)"));
        }
    }

    @Test void forkCopiesStateAndChildrenRemainIndependent() throws Exception {
        Model model = model();
        var provider = new FauxProvider("faux", "faux", List.of(model));
        try (var root = new CodingAgentOperations()) {
            root.applicationPaths(new CodingAgentPaths(workspace.resolve("home")));
            root.configureAgent(provider, model, workspace, "Context", null, ThinkingLevel.OFF);
            root.sessionStore(workspace.resolve("sessions"), List.of());
            root.createSessionRecorder(workspace, "faux", "faux-1");
            root.setSessionRecording(true, error -> fail(error));
            AgentTool mainTool = tool(root, workspace);
            call(root, mainTool, action("add_task").put("description", "Main task"));
            String source = root.state().sessionId();
            String childId = root.subagents().create("Review", "Reviewer");
            CodingAgentOperations child = root.subagents().runtime(childId);
            AgentTool childTool = tool(child, workspace);
            assertTrue(call(child, childTool, action("list")).contains("Tasks: (none)"));
            call(child, childTool, action("add_task").put("description", "Child task"));
            assertFalse(call(root, mainTool, action("list")).contains("Child task"));
            assertFalse(call(child, childTool, action("list")).contains("Main task"));
            ObjectNode copy = root.taskStateSnapshot();
            root.forkSessionRecorder(workspace, "faux", "faux-1", "Branch", List.of());
            String fork = root.state().sessionId();
            assertEquals("Main task", root.sessionSnapshot(fork).taskState.path("tasks").get(0).path("description").asText());
            root.configureAgent(provider, model, workspace, "Context", null, ThinkingLevel.OFF);
            root.restoreTaskState(copy); // /fork also does this when --no-session is set.
            assertTrue(call(root, tool(root, workspace), action("list")).contains("Main task"));
            call(root, tool(root, workspace), action("add_task").put("description", "Branch task"));
            assertEquals(1, root.sessionSnapshot(source).taskState.path("tasks").size());
            assertEquals(1, root.sessionSnapshot(fork).taskState.path("tasks").size());
            assertFalse(call(root, tool(root, workspace), action("list")).contains("Child task"));
        }
    }

    @Test void guidanceIsAvailableInBothModesAndSnapshotRestoresCounters() throws Exception {
        for (AgentMode mode : AgentMode.values()) {
            assertTrue(mode.instructions().contains("task_state list"));
            assertTrue(mode.instructions().contains("Revise or cancel tasks"));
        }
        TaskState state = new TaskState(ignored -> {});
        state.apply("add_task", null, "First", null, null, List.of(), false, null);
        state.apply("remove_task", "#1", null, null, null, List.of(), false, null);
        TaskState resumed = new TaskState(ignored -> {});
        resumed.restore(state.snapshot());
        assertTrue(resumed.apply("add_task", null, "Next", null, null, List.of(), false, null).contains("#2 [todo] Next"));
    }

    private static Model model() {
        Model model = new Model();
        model.id = "faux-1"; model.name = "Faux"; model.api = "faux"; model.provider = "faux";
        model.contextWindow = 100_000; model.maxTokens = 4096;
        return model;
    }
}
