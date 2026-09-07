package com.quaxt.codingagent;

import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static com.quaxt.codingagent.CodingAgentOperations.text;
import static com.quaxt.codingagent.CodingAgentOperations.userMessage;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentRuntimeTest {
    @TempDir Path workspace;
    private final CodingAgentOperations runtime = CodingAgentOperations.INSTANCE;

    @Test
    void executesToolsAndRecordsPromptsAndCheckpointsWithoutATerminal() throws Exception {
        FauxProvider provider = configure(100_000);
        AssistantMessage toolUse = answer("Writing the file");
        toolUse.stopReason = StopReason.TOOL_USE;
        toolUse.content.add(new ToolCall("write-1", "write",
                jsonObject().put("path", "result.txt").put("content", "done"), null));
        provider.pendingResponses.add(new FauxProvider.ResponseStep.Message(toolUse));
        enqueue(provider, "File written", "Created result.txt", "Continuing from the checkpoint");
        List<AgentEvent> events = new ArrayList<>();
        runtime.subscribe(events::add);
        recordSession();

        assertEquals(4, runtime.prompt("Create result.txt").size());
        assertEquals("done", Files.readString(workspace.resolve("result.txt")));
        assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionEnd.class::isInstance));
        assertFalse(runtime.state().streaming());

        runtime.compact(null);
        runtime.prompt("Continue");

        String sessionId = runtime.state().sessionId();
        var entries = runtime.readSession(sessionId);
        assertEquals(8, entries.size()); // Start, four messages, checkpoint, two more messages.
        assertEquals(1, entries.stream().filter(entry -> entry.type.equals("compaction")).count());
        var resumed = runtime.sessionSnapshot(sessionId);
        assertEquals(3, resumed.messages.size());
        assertTrue(text((com.quaxt.codingagent.ai.types.UserMessage) resumed.messages.getFirst())
                .contains("Created result.txt"));
        assertEquals("Continuing from the checkpoint", text((AssistantMessage) resumed.messages.getLast()));
    }

    @Test
    void automaticallyCompactsAndPersistsTheResumeBoundary() throws Exception {
        FauxProvider provider = configure(16_390);
        List<Message> history = List.of(userMessage("A long previous question ".repeat(10)), answer("A previous answer"));
        runtime.restoreMessages(history);
        recordSession();
        runtime.appendSessionMessages(history);
        enqueue(provider, "Earlier work summarized", "Next answer");

        runtime.prompt("Next question");

        var entries = runtime.readSession(runtime.state().sessionId());
        assertEquals(List.of("session_start", "message", "message", "compaction", "message", "message"),
                entries.stream().map(entry -> entry.type).toList());
        assertEquals(3, runtime.sessionSnapshot(runtime.state().sessionId()).messages.size());
        assertFalse(runtime.state().compacting());
    }

    @Test
    void reportsCheckpointPersistenceFailureAndKeepsTheCompactedConversation() throws Exception {
        FauxProvider provider = configure(100_000);
        runtime.restoreMessages(List.of(userMessage("Summarize the work"), answer("Work completed")));
        recordSession();
        List<IOException> failures = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        runtime.setSessionRecording(true, failures::add);
        runtime.subscribe(events::add);
        Files.delete(workspace.resolve("sessions").resolve(runtime.state().sessionId() + ".jsonl"));
        enqueue(provider, "Checkpoint retained in memory");

        runtime.compact(null);

        assertEquals(1, failures.size());
        assertEquals(1, runtime.state().messages().size());
        assertTrue(events.stream().anyMatch(AgentEvent.CompactionEnd.class::isInstance));
        assertFalse(runtime.state().compacting());
    }

    @Test
    void exposesConversationSnapshotsAndDiscardsOldListenersWhenReconfigured() throws Exception {
        configure(100_000);
        List<Message> restored = new ArrayList<>(List.of(userMessage("Restored question")));
        runtime.restoreMessages(restored);
        restored.clear();
        var snapshot = runtime.state();
        assertEquals(1, snapshot.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.messages().clear());
        snapshot.model().id = "changed outside the runtime";
        assertEquals("faux-1", runtime.state().model().id);
        List<AgentEvent> previousEvents = new ArrayList<>();
        runtime.subscribe(previousEvents::add);

        FauxProvider provider = configure(100_000);
        enqueue(provider, "Fresh answer");
        runtime.prompt("Fresh question");

        assertTrue(previousEvents.isEmpty());
        assertEquals(2, runtime.state().messages().size());
        assertEquals(1, snapshot.messages().size());
    }

    private FauxProvider configure(long contextWindow) {
        Model model = new Model();
        model.id = "faux-1";
        model.name = "Faux";
        model.api = "faux";
        model.provider = "faux";
        model.contextWindow = contextWindow;
        model.maxTokens = 4_096;
        FauxProvider provider = new FauxProvider("faux", "faux", List.of(model));
        runtime.configureAgent(provider, model, workspace, "", null, ThinkingLevel.OFF);
        runtime.setAutoCompaction(true);
        return provider;
    }

    private void recordSession() throws IOException {
        runtime.sessionStore(workspace.resolve("sessions"), List.of());
        runtime.createSessionRecorder(workspace, "faux", "faux-1");
        runtime.setSessionRecording(true, error -> fail("Could not record checkpoint", error));
    }

    private static AssistantMessage answer(String text) {
        AssistantMessage message = new AssistantMessage("faux", "faux", "faux-1");
        message.content.add(new TextContent(text, null));
        message.stopReason = StopReason.STOP;
        return message;
    }

    private static void enqueue(FauxProvider provider, String... answers) {
        for (String text : answers) provider.pendingResponses.add(new FauxProvider.ResponseStep.Message(answer(text)));
    }
}
