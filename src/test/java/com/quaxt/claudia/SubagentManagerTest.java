package com.quaxt.claudia;

import com.quaxt.claudia.agent.AgentEvent;
import com.quaxt.claudia.ai.util.AbortSignal;
import com.quaxt.claudia.ai.providers.FauxProvider;
import com.quaxt.claudia.ai.types.*;
import com.quaxt.claudia.ai.json.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.quaxt.claudia.ClaudiaOperations.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class SubagentManagerTest {
    @TempDir Path workspace;
    ClaudiaOperations root;
    FauxProvider provider;
    Model model;

    @BeforeEach void setup() {
        root = new ClaudiaOperations();
        root.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
        model = new Model();
        model.id = "faux-1"; model.name = "Faux"; model.api = "faux"; model.provider = "faux";
        model.contextWindow = 100_000; model.maxTokens = 4096; model.reasoning = true;
        provider = new FauxProvider("faux", "faux", List.of(model));
        root.configureAgent(provider, model, workspace, "Base instructions", "inherited-key", ThinkingLevel.HIGH);
    }

    @AfterEach void close() { root.close(); }

    static AssistantMessage answer(String text) {
        var answer = new AssistantMessage("faux", "faux", "faux-1");
        answer.content.add(new TextContent(text, null)); answer.stopReason = StopReason.STOP;
        return answer;
    }

    void step(Function<FauxProvider.Request, AssistantMessage> factory) {
        synchronized (provider) { provider.pendingResponses.add(new FauxProvider.ResponseStep.Factory(factory)); }
    }

    void reply(String text) { step(request -> answer(text)); }

    static String prompt(FauxProvider.Request request) { return text((UserMessage) request.context.messages.getLast()); }

    static AssistantMessage delegate(String task, String id) {
        var answer = answer(""); answer.stopReason = StopReason.TOOL_USE;
        var args = jsonObject().put("task", task).put("name", "Investigator");
        if (id != null) args.put("agent_id", id);
        answer.content.add(new ToolCall("delegate-" + task, "subagent", args, null));
        return answer;
    }

    void record() throws Exception {
        root.sessionStore(workspace.resolve("sessions"), List.of());
        root.createSessionRecorder(workspace, model.provider, model.id);
        root.setSessionRecording(true, error -> fail(error));
    }

    @Test void isolatesContextAndEventsAndReturnsOnlyTheRequestedFinalAnswer() throws Exception {
        List<AgentEvent> rootEvents = new CopyOnWriteArrayList<>();
        List<SubagentManager.Event> groupEvents = new CopyOnWriteArrayList<>();
        root.subscribe(rootEvents::add); root.subagents().subscribe(groupEvents::add);
        step(request -> {
            assertTrue(request.context.tools.stream().anyMatch(tool -> tool.name.equals("subagent")));
            return delegate("Investigate", null);
        });
        step(request -> {
            assertEquals(1, request.context.messages.size());
            assertEquals("Investigate", prompt(request));
            assertTrue(request.context.systemPrompt.contains("Base instructions"));
            assertEquals(ThinkingLevel.HIGH, request.options.reasoning);
            assertEquals("inherited-key", request.options.apiKey);
            assertFalse(request.context.tools.stream().anyMatch(tool -> tool.name.equals("subagent")));
            var response = answer("child final");
            response.content.addFirst(new ThinkingContent("private investigation", null, false));
            return response;
        });
        step(request -> {
            ToolResultMessage result = (ToolResultMessage) request.context.messages.getLast();
            assertFalse(result.isError);
            var data = (com.fasterxml.jackson.databind.JsonNode) result.details;
            assertEquals("child final", data.path("final_answer").asText());
            assertEquals("completed", data.path("status").asText());
            assertFalse(request.context.messages.toString().contains("private investigation"));
            return answer("main final");
        });
        assertEquals("main final", text((AssistantMessage) root.prompt("private parent context").getLast()));
        assertEquals(2, root.subagents().list().size());
        String id = root.subagents().list().getLast().id();
        assertTrue(groupEvents.stream().anyMatch(event -> event.agentId().equals(id)));
        assertFalse(rootEvents.stream().anyMatch(event -> event instanceof AgentEvent.MessageEnd end
                && end.message instanceof AssistantMessage assistant && text(assistant).equals("child final")));
        assertEquals("Main", ClaudiaCli.subagentItems(root.subagents().list(), id).getFirst().label);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u2003"})
    void nullOrBlankAgentIdCreatesANewChild(String agentId) throws Exception {
        reply("child final");
        var result = root.executeTool(root.subagents().tool(), "new-child",
                jsonObject().put("task", "Investigate").put("name", "Investigator").put("agent_id", agentId),
                new AbortSignal(), ignored -> {});

        assertFalse(result.isError);
        var data = (com.fasterxml.jackson.databind.JsonNode) result.details;
        String childId = data.path("agent_id").asText();
        assertFalse(childId.isBlank());
        assertNotEquals(SubagentManager.MAIN, childId);
        assertEquals("completed", data.path("status").asText());
        assertEquals("child final", data.path("final_answer").asText());
        assertEquals(2, root.subagents().list().size());
        var child = root.subagents().snapshot(childId);
        assertEquals("Investigator", child.name());
        assertEquals("Investigate", child.task());
        assertEquals("Investigate", text((UserMessage) child.transcript().getFirst()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "missing-child", " main "})
    void invalidNonblankAgentIdsDoNotCreateChildren(String agentId) {
        assertThrows(IllegalArgumentException.class, () -> root.executeTool(root.subagents().tool(), "invalid-child",
                jsonObject().put("task", "Investigate").put("agent_id", agentId), new AbortSignal(), ignored -> {}));
        assertEquals(1, root.subagents().list().size());
        assertTrue(provider.pendingResponses.isEmpty());
    }

    @Test void directChatsAndDelegatedRequestsHaveSeparateResultsAndSerialContexts() throws Exception {
        String id = root.subagents().create("original brief", "Worker");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        step(request -> {
            started.countDown(); await(release);
            assertEquals("first", prompt(request)); return answer("first answer");
        });
        step(request -> {
            assertEquals(3, request.context.messages.size());
            assertEquals("direct chat", prompt(request)); return answer("chat answer");
        });
        var first = root.subagents().submit(id, "first");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        var chat = root.subagents().submit(id, "direct chat");
        assertEquals(1, root.subagents().snapshot(id).queued());
        release.countDown();
        assertEquals("first answer", first.get().finalAnswer());
        assertEquals("chat answer", chat.get().finalAnswer());
        step(request -> delegate("follow-up", id));
        step(request -> {
            assertEquals(5, request.context.messages.size());
            assertEquals("follow-up", prompt(request)); return answer("delegated answer");
        });
        reply("main done");
        root.prompt("ask worker again");
        assertEquals(2, root.subagents().list().size());
        assertEquals(6, root.subagents().snapshot(id).transcript().size());
    }

    @Test void cancellationClearsQueueAndReleasesDelegationAndAllowsRecovery() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        step(request -> delegate("blocked", null));
        step(request -> { started.countDown(); await(release); return answer("discarded"); });
        reply("handled cancellation");
        var parent = root.subagents().submit(SubagentManager.MAIN, "delegate");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        String id = root.subagents().list().getLast().id();
        var queued = root.subagents().submit(id, "queued");
        root.subagents().cancel(id);
        release.countDown();
        assertThrows(CancellationException.class, queued::join);
        assertEquals("handled cancellation", parent.get().finalAnswer());
        ToolResultMessage error = root.state().messages().stream().filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast).findFirst().orElseThrow();
        assertTrue(error.isError); assertTrue(text(error).contains(id));
        reply("recovered");
        assertEquals("recovered", root.subagents().submit(id, "recover").get().finalAnswer());
    }

    @Test void failedChildCanBeReusedAndForeignIdsAreRejected() throws Exception {
        String id = root.subagents().create("task", null);
        step(request -> { var response = answer(""); response.stopReason = StopReason.ERROR; response.errorMessage = "invalid request"; return response; });
        assertThrows(ExecutionException.class, () -> root.subagents().submit(id, "fail").get());
        assertEquals(SubagentManager.Status.FAILED, root.subagents().snapshot(id).status());
        reply("recovered");
        assertEquals("recovered", root.subagents().submit(id, "recover").get().finalAnswer());
        assertThrows(IllegalArgumentException.class, () -> root.subagents().submit(uuidv7(), "foreign"));
        assertThrows(IllegalStateException.class, () -> root.subagents().runtime(id).subagents());
    }

    @Test void persistsParentLinkBeforeWorkAndRestoresChildrenLazily() throws Exception {
        record(); String parent = root.state().sessionId();
        String id = root.subagents().create("saved brief", "Saved worker");
        assertEquals(parent, root.sessionSnapshot(id).parentSessionId);
        assertEquals(1, root.listSessions(workspace).size());
        reply("investigated"); root.subagents().submit(id, "investigate").get();
        ClaudiaOperations child = root.subagents().runtime(id);
        reply("checkpoint"); child.compact(null);
        child.recordLifecycle("RUNNING"); // Simulate a process ending during the next task.
        root.configureAgent(provider, model, workspace, "Base instructions", null, ThinkingLevel.HIGH);
        root.resumeSessionRecorder(parent); root.setSessionRecording(true, error -> fail(error));
        var restored = root.subagents().snapshot(id);
        assertEquals(SubagentManager.Status.INTERRUPTED, restored.status());
        assertEquals(2, restored.transcript().size());
        assertEquals(1, restored.state().messages().size());
        assertEquals(ThinkingLevel.HIGH, restored.state().thinkingLevel());
        step(request -> { assertEquals(2, request.context.messages.size()); return answer("continued"); });
        root.subagents().submit(id, "continue").get();
        assertEquals(4, root.sessionSnapshot(id).transcriptMessages.size());
        assertEquals("COMPLETED", root.sessionSnapshot(id).lifecycle);
    }

    @Test void checkpointsAcceptedStepsBeforeThePromptCompletes() throws Exception {
        record(); String id = root.subagents().create("task", "Worker");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        step(request -> { started.countDown(); await(release); return answer("done"); });
        var future = root.subagents().submit(id, "accepted prompt");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertEquals("accepted prompt", text((UserMessage) root.sessionSnapshot(id).messages.getFirst()));
        release.countDown(); future.get();
        assertEquals(2, root.sessionSnapshot(id).messages.size());
    }

    @Test void memoryOnlyAndFreshSessionReleaseChildren() throws Exception {
        String id = root.subagents().create("task", null);
        assertTrue(root.subagents().snapshot(id).transcript().isEmpty());
        reply("done"); root.subagents().submit(id, "task").get();
        var old = root.subagents().runtime(id);
        assertNull(old.state().sessionId());
        assertFalse(Files.exists(workspace.resolve("sessions")));
        root.configureAgent(provider, model, workspace, "", null, ThinkingLevel.OFF);
        assertEquals(1, root.subagents().list().size());
        assertThrows(IllegalStateException.class, () -> old.prompt("closed"));
    }

    @Test void inheritsWorkspaceInstructionsAndMcpConfigurationWithIndependentResources() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "Workspace instruction: include the verification result.");
        var config = new com.quaxt.claudia.mcp.McpServerConfig.Local(List.of("unused-command"), null,
                Map.of("SETTING", "value"), false, 1000L, List.of(), List.of("disabled_tool"));
        var builtIn = new com.quaxt.claudia.mcp.McpServerConfig.Local(List.of("unused-code-lens", "mcp"), null,
                Map.of(), false, null, List.of(), List.of(), true);
        root.mcpCreateManager(new com.quaxt.claudia.mcp.McpConfiguration(
                Map.of("fixture", config, "code-lens", builtIn), List.of()), workspace);
        String id = root.subagents().create("write a file", "Writer");
        step(request -> {
            assertTrue(request.context.systemPrompt.contains("Workspace instruction"));
            var response = answer(""); response.stopReason = StopReason.TOOL_USE;
            response.content.add(new ToolCall("write", "write", jsonObject().put("path", "child.txt").put("content", "written"), null));
            return response;
        });
        reply("written"); root.subagents().submit(id, "write a file").get();
        var child = root.subagents().runtime(id);
        assertNotSame(root, child);
        assertEquals(root.applicationPaths(), child.applicationPaths());
        assertEquals("written", Files.readString(workspace.resolve("child.txt")));
        assertEquals(ClaudiaOperations.McpState.DISABLED, child.mcpStatus("fixture").state);
        assertFalse(child.mcpStatus("fixture").builtIn);
        assertTrue(child.mcpStatus("code-lens").builtIn);
        for (String fieldName : List.of("shellSessions", "servers", "providerStates")) {
            var field = ClaudiaOperations.class.getDeclaredField(fieldName); field.setAccessible(true);
            assertNotSame(field.get(root), field.get(child));
        }
        var snapshot = root.subagents().snapshot(id);
        ((TextContent) ((UserMessage) snapshot.state().messages().getFirst()).content.getFirst()).text = "mutated by observer";
        assertEquals("write a file", text((UserMessage) child.state().messages().getFirst()));
    }

    @Test void savesAcceptedToolStepsButNotDiscardedProviderRetries() throws Exception {
        record();
        var retry = ClaudiaOperations.class.getDeclaredField("retryPolicy"); retry.setAccessible(true);
        retry.set(root, new com.quaxt.claudia.ai.Retry.Policy(true, 1, 1));
        String id = root.subagents().create("inspect", null);
        step(request -> {
            var response = answer("accepted step"); response.stopReason = StopReason.TOOL_USE;
            response.content.add(new ToolCall("inspect", "ls", jsonObject(), null)); return response;
        });
        step(request -> {
            var response = answer("discarded retry"); response.stopReason = StopReason.ERROR;
            response.errorMessage = "503 overloaded"; return response;
        });
        reply("final answer"); root.subagents().submit(id, "inspect").get();
        var saved = root.sessionSnapshot(id);
        assertEquals(4, saved.messages.size());
        assertFalse(saved.allMessagesText.contains("discarded retry"));
        assertTrue(saved.allMessagesText.contains("accepted step"));
        assertEquals(4, root.readSession(id).stream().filter(entry -> entry.type.equals("message")).count());
    }

    @Test void cancellingMainCancelsEveryMailboxAndClosingReleasesChildren() throws Exception {
        String first = root.subagents().create("first", null), second = root.subagents().create("second", null);
        CountDownLatch started = new CountDownLatch(3), release = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) step(request -> { started.countDown(); await(release); return answer("cancelled"); });
        var one = root.subagents().submit(first, "first");
        var two = root.subagents().submit(second, "second");
        var main = root.subagents().submit(SubagentManager.MAIN, "main");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        var queued = root.subagents().submit(second, "later");
        ClaudiaOperations firstRuntime = root.subagents().runtime(first), secondRuntime = root.subagents().runtime(second);
        root.abort(); release.countDown();
        for (var future : List.of(one, two, main, queued)) assertThrows(CancellationException.class, future::join);
        root.close();
        assertThrows(IllegalStateException.class, () -> firstRuntime.prompt("closed"));
        assertThrows(IllegalStateException.class, () -> secondRuntime.prompt("closed"));
        assertThrows(IllegalStateException.class, () -> root.subagents().submit(first, "closed manager"));
    }

    @Test void compactionKeepsTheMailboxAvailableForQueuedChatsAndCancellation() throws Exception {
        String id = root.subagents().create("task", null);
        var child = root.subagents().runtime(id);
        child.restoreMessages(List.of(userMessage("previous task"), answer("previous work")));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        step(request -> { started.countDown(); await(release); return answer("checkpoint"); });
        var compact = root.subagents().compact(id);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        var chat = root.subagents().submit(id, "queued chat");
        assertEquals(1, root.subagents().snapshot(id).queued());
        assertTrue(root.subagents().snapshot(id).state().compacting());
        root.subagents().cancel(id); release.countDown();
        assertThrows(CancellationException.class, compact::join);
        assertThrows(CancellationException.class, chat::join);
        reply("recovered");
        assertEquals("recovered", root.subagents().submit(id, "continue").get().finalAnswer());
        assertEquals(2, root.subagents().list().size());
    }

    static void await(CountDownLatch latch) {
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
