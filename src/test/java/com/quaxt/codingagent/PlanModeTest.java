package com.quaxt.codingagent;

import com.quaxt.codingagent.agent.*;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.types.*;
import com.quaxt.codingagent.ai.util.AbortSignal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.quaxt.codingagent.CodingAgentOperations.*;
import static com.quaxt.codingagent.SubagentManagerTest.answer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class PlanModeTest {
    @TempDir Path workspace;
    CodingAgentOperations runtime;
    Model model;
    FauxProvider provider;

    @BeforeEach void setup() throws Exception {
        Files.createDirectory(workspace.resolve(".git"));
        runtime = new CodingAgentOperations();
        runtime.applicationPaths(new CodingAgentPaths(workspace.resolve("home")));
        model = new Model(); model.id = "faux-1"; model.name = "Faux"; model.api = "faux"; model.provider = "faux";
        model.contextWindow = 100_000; model.maxTokens = 4096;
        provider = new FauxProvider("faux", "faux", List.of(model));
        configure();
    }

    void configure() { runtime.configureAgent(provider, model, workspace, "Project context", null, ThinkingLevel.OFF); }
    @AfterEach void close() { runtime.close(); }
    void step(Function<FauxProvider.Request, AssistantMessage> factory) {
        provider.pendingResponses.add(new FauxProvider.ResponseStep.Factory(factory));
    }
    void record() throws Exception {
        runtime.sessionStore(workspace.resolve("sessions"), List.of());
        runtime.createSessionRecorder(workspace, model.provider, model.id);
        runtime.setSessionRecording(true, error -> fail(error));
    }
    static AssistantMessage question(String text) {
        var response = answer(""); response.stopReason = StopReason.TOOL_USE;
        var arguments = jsonObject().put("question", text);
        arguments.putArray("options").addObject().put("label", "Small").put("description", "Less work");
        response.content.add(new ToolCall("question-call", "question", arguments, null));
        return response;
    }
    static void assertMode(FauxProvider.Request request, AgentMode mode) {
        String instructions = request.context.systemPrompt;
        assertTrue(instructions.contains("Current mode: " + mode.label));
        assertTrue(instructions.contains("task_state list"));
        assertEquals(1, instructions.split("<agent_mode>", -1).length - 1);
        assertTrue(instructions.indexOf("Project context") < instructions.indexOf("<agent_mode>"));
    }

    @Test void togglesWithoutCallsOrHistoryChangesAndSurvivesInstructionRefresh() throws Exception {
        Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("nested/AGENTS.md"), "Nested instruction");
        runtime.setAgentMode(AgentMode.PLAN);
        assertTrue(runtime.state().messages().isEmpty());
        step(request -> {
            assertMode(request, AgentMode.PLAN);
            assertTrue(request.context.tools.stream().map(tool -> tool.name).toList().containsAll(List.of("write", "edit", "shell", "question")));
            var response = answer(""); response.stopReason = StopReason.TOOL_USE;
            response.content.add(new ToolCall("read-nested", "read", jsonObject().put("path", "nested/AGENTS.md"), null));
            return response;
        });
        step(request -> {
            assertMode(request, AgentMode.PLAN);
            assertTrue(request.context.systemPrompt.contains("Nested instruction"));
            return answer("Proposed changes and verification");
        });
        runtime.prompt("Investigate");
        var before = runtime.state();
        runtime.setAgentMode(AgentMode.BUILD);
        assertEquals(before.messages().size(), runtime.state().messages().size());
        assertEquals(before.sessionId(), runtime.state().sessionId());
        assertEquals(before.model().id, runtime.state().model().id);
        step(request -> { assertMode(request, AgentMode.BUILD); return answer("Implementing the agreed approach"); });
        runtime.prompt("Implement the plan");
        assertTrue(provider.pendingResponses.isEmpty());
    }

    @Test void compactionPreservesPlanningInformationAndModeRemainsOutsideHistory() throws Exception {
        runtime.setAgentMode(AgentMode.PLAN);
        runtime.restoreMessages(List.of(userMessage("Plan the feature"), answer("Plan: change X, verify Y; chose small scope.")));
        step(request -> {
            assertTrue(request.context.systemPrompt.contains("implementation plan"));
            assertTrue(request.context.systemPrompt.contains("explicit answers"));
            assertTrue(text((UserMessage) request.context.messages.getFirst()).contains("chose small scope"));
            assertTrue(request.context.tools.isEmpty());
            return answer("Plan: change X, verify Y; small scope agreed.");
        });
        runtime.compact(null);
        step(request -> { assertMode(request, AgentMode.PLAN); return answer("Refined plan"); });
        runtime.prompt("Refine it");
        assertEquals(AgentMode.PLAN, runtime.state().agentMode());
        model.contextWindow = 16_390;
        runtime.restoreMessages(List.of(userMessage("Planning detail ".repeat(100)), answer("Draft plan")));
        step(request -> answer("Plan retained in checkpoint"));
        step(request -> { assertMode(request, AgentMode.PLAN); return answer("Ready to review"); });
        runtime.prompt("Continue planning");
        assertTrue(text((UserMessage) runtime.state().messages().getFirst()).contains("checkpoint"));
    }

    @Test void modePersistsAcrossResumeForkAndReconfigurationAndControlsEveryChild() throws Exception {
        runtime.setAgentMode(AgentMode.PLAN);
        record();
        String mainId = runtime.state().sessionId();
        String lazy = runtime.subagents().create("Investigate", "Lazy");
        var child = runtime.subagents().runtime(runtime.subagents().create("Review", "Live"));
        assertEquals(AgentMode.PLAN, child.agentMode());
        runtime.setAgentMode(AgentMode.BUILD);
        assertTrue(runtime.subagents().list().stream().allMatch(agent -> agent.state().agentMode() == AgentMode.BUILD));
        assertEquals(AgentMode.BUILD, runtime.subagents().runtime(lazy).agentMode());
        assertThrows(IllegalStateException.class, () -> child.setAgentMode(AgentMode.PLAN));
        runtime.setAgentMode(AgentMode.PLAN);
        assertEquals(AgentMode.PLAN, runtime.sessionSnapshot(mainId).agentMode);
        configure();
        assertEquals(AgentMode.PLAN, runtime.agentMode());
        runtime.setAgentMode(AgentMode.BUILD);
        runtime.resumeSessionRecorder(mainId);
        assertEquals(AgentMode.PLAN, runtime.agentMode());
        assertEquals(3, runtime.subagents().list().size());
        assertEquals(AgentMode.PLAN, runtime.subagents().runtime(lazy).agentMode());
        runtime.forkSessionRecorder(workspace, model.provider, model.id, "Fork", runtime.state().messages());
        assertEquals(AgentMode.PLAN, runtime.sessionSnapshot(runtime.state().sessionId()).agentMode);
        assertNotEquals(mainId, runtime.state().sessionId());
        try (var independent = new CodingAgentOperations()) { assertEquals(AgentMode.BUILD, independent.agentMode()); }
    }

    @Test void legacySessionsDefaultToBuildAndSaveFailuresRetainTheLiveSelection() throws Exception {
        record();
        String id = runtime.state().sessionId();
        Path file = workspace.resolve("sessions/" + id + ".jsonl");
        String saved = Files.readString(file);
        Files.writeString(file, saved.replace(",\"agentMode\":\"build\"", ""));
        assertEquals(AgentMode.BUILD, runtime.sessionSnapshot(id).agentMode);
        var failures = new ArrayList<java.io.IOException>();
        runtime.setSessionRecording(true, failures::add);
        Files.delete(file);
        runtime.setAgentMode(AgentMode.PLAN);
        assertEquals(AgentMode.PLAN, runtime.agentMode());
        assertEquals(1, failures.size());
    }

    @Test void questionSuspendsItsTurnAndDoesNotConsumeQueuedChatAsAnAnswer() throws Exception {
        runtime.setAgentMode(AgentMode.PLAN);
        runtime.questions().setInteractive(true);
        record();
        var requested = new CompletableFuture<QuestionBroker.Request>();
        runtime.questions().subscribe(event -> { if (event.type().equals("question_requested")) requested.complete(event.request()); });
        step(request -> question("Which scope?"));
        step(request -> {
            var result = (ToolResultMessage) request.context.messages.getLast();
            assertTrue(text(result).contains("Custom scope"));
            assertTrue(text(result).contains("answered"));
            return answer("Plan using Custom scope");
        });
        step(request -> {
            assertEquals("Follow-up chat", text((UserMessage) request.context.messages.getLast()));
            return answer("Follow-up handled");
        });
        var turn = runtime.subagents().submit(SubagentManager.MAIN, "Plan this");
        var request = requested.get(3, TimeUnit.SECONDS);
        var queued = runtime.subagents().submit(SubagentManager.MAIN, "Follow-up chat");
        assertFalse(turn.isDone()); assertFalse(queued.isDone());
        assertEquals(1, runtime.questions().pending().size());
        assertThrows(IllegalStateException.class, () -> runtime.setAgentMode(AgentMode.BUILD));
        runtime.questions().answer(request.questionId(), "Custom scope");
        assertEquals("Plan using Custom scope", turn.get(3, TimeUnit.SECONDS).finalAnswer());
        queued.get(3, TimeUnit.SECONDS);
        assertThrows(IllegalArgumentException.class, () -> runtime.questions().answer(request.questionId(), "Duplicate"));
        assertTrue(runtime.sessionSnapshot(runtime.state().sessionId()).allMessagesText.contains("Custom scope"));
        runtime.setAgentMode(AgentMode.BUILD);
    }

    @Test void delegatedQuestionsKeepTheirOwnerAndOtherAgentsCanContinue() throws Exception {
        runtime.questions().setInteractive(true);
        String first = runtime.subagents().create("First", "First");
        String second = runtime.subagents().create("Second", "Second");
        var requests = new LinkedBlockingQueue<QuestionBroker.Request>();
        runtime.questions().subscribe(event -> { if (event.type().equals("question_requested")) requests.add(event.request()); });
        step(request -> question("First question"));
        var firstTurn = runtime.subagents().submit(first, "First");
        var firstQuestion = requests.poll(3, TimeUnit.SECONDS);
        assertNotNull(firstQuestion); assertEquals(first, firstQuestion.agentId());
        step(request -> question("Second question"));
        var secondTurn = runtime.subagents().submit(second, "Second");
        var secondQuestion = requests.poll(3, TimeUnit.SECONDS);
        assertNotNull(secondQuestion); assertEquals(second, secondQuestion.agentId());
        step(request -> answer("Second answered"));
        runtime.questions().answer(secondQuestion.questionId(), "Small");
        secondTurn.get(3, TimeUnit.SECONDS);
        assertFalse(firstTurn.isDone());
        step(request -> {
            assertTrue(text((ToolResultMessage) request.context.messages.getLast()).contains("declined"));
            return answer("First remains unresolved");
        });
        runtime.questions().decline(firstQuestion.questionId());
        firstTurn.get(3, TimeUnit.SECONDS);
    }

    @Test void unavailableQuestionsNeverWaitAndInvalidArgumentsNeverReachTheHost() throws Exception {
        var tool = runtime.builtInTools(workspace, ignored -> {}).stream().filter(t -> toolName(t).equals("question")).findFirst().orElseThrow();
        var result = runtime.executeTool(tool, "q", jsonObject().put("question", "Which scope?"), new AbortSignal(), ignored -> {});
        assertEquals("unavailable", ((com.fasterxml.jackson.databind.JsonNode) result.details).path("status").asText());
        assertTrue(runtime.questions().pending().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runtime.executeTool(tool, "q", jsonObject().put("question", " "), new AbortSignal(), ignored -> {}));
        var args = jsonObject().put("question", "Which?");
        args.putArray("options").addObject().put("label", "");
        assertThrows(IllegalArgumentException.class, () -> runtime.executeTool(tool, "q", args, new AbortSignal(), ignored -> {}));
    }

    @Test void disablingInteractionReleasesPendingQuestionsAndFutureCalls() throws Exception {
        runtime.questions().setInteractive(true);
        var requested = new CountDownLatch(1);
        runtime.questions().subscribe(event -> { if (event.type().equals("question_requested")) requested.countDown(); });
        var future = CompletableFuture.supplyAsync(() -> {
            try { return runtime.questions().ask("main", "Scope?", List.of(), new AbortSignal()); }
            catch (InterruptedException error) { throw new CompletionException(error); }
        });
        assertTrue(requested.await(3, TimeUnit.SECONDS));
        runtime.questions().setInteractive(false);
        assertEquals("unavailable", future.get(3, TimeUnit.SECONDS).status());
        assertEquals("unavailable", runtime.questions().ask("main", "Next?", List.of(), new AbortSignal()).status());
        assertTrue(runtime.questions().pending().isEmpty());
    }

    @Test void synchronousHostRepliesDeliverOrderedEventsToEveryObserver() throws Exception {
        runtime.questions().setInteractive(true);
        runtime.questions().subscribe(event -> {
            if (event.type().equals("question_requested")) runtime.questions().answer(event.request().questionId(), "Small");
        });
        var events = new ArrayList<String>();
        runtime.questions().subscribe(event -> events.add(event.type()));
        assertEquals("Small", runtime.questions().ask("main", "Scope?", List.of(), new AbortSignal()).answer());
        assertEquals(List.of("question_requested", "question_resolved"), events);
        assertTrue(runtime.questions().pending().isEmpty());
    }

    @Test void abortReleasesQuestionAndRestoringTranscriptDoesNotReopenIt() throws Exception {
        runtime.questions().setInteractive(true);
        var requested = new CountDownLatch(1);
        runtime.questions().subscribe(event -> { if (event.type().equals("question_requested")) requested.countDown(); });
        step(request -> question("Scope?"));
        var turn = runtime.subagents().submit(SubagentManager.MAIN, "Plan");
        assertTrue(requested.await(3, TimeUnit.SECONDS));
        runtime.abort();
        assertThrows(CancellationException.class, turn::join);
        assertTrue(runtime.questions().pending().isEmpty());
        var transcript = runtime.transcript();
        try (var resumed = new CodingAgentOperations()) {
            resumed.configureAgent(provider, model, workspace, "", null, ThinkingLevel.OFF);
            resumed.restoreMessages(transcript);
            assertTrue(resumed.questions().pending().isEmpty());
        }
    }
}
