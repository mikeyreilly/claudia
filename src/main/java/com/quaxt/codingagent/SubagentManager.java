package com.quaxt.codingagent;

import com.quaxt.codingagent.agent.*;
import com.quaxt.codingagent.ai.types.*;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.quaxt.codingagent.agent.ToolParameters.*;

/** Root-owned conversation group. Each mailbox has exactly one serial consumer. */
public final class SubagentManager implements AutoCloseable {
    public static final String MAIN = "main";
    public enum Status { IDLE, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
    public record Answer(String agentId, String name, Status status, String finalAnswer) {}
    public record Snapshot(String id, String name, String task, Status status, int queued,
                           CodingAgentOperations.AgentSnapshot state, List<Message> transcript) {}
    public record Event(String agentId, AgentEvent event) {}
    private record Request(String prompt, String compactionInstructions, CompletableFuture<Answer> result) {}
    private static final class Entry {
        final String id, name, task;
        final Deque<Request> queue = new ArrayDeque<>();
        Model model;
        ThinkingLevel thinkingLevel;
        CodingAgentOperations runtime;
        SessionSnapshot saved;
        Status status = Status.IDLE;
        Thread worker;
        Request current;
        Entry(String id, String name, String task) { this.id = id; this.name = name; this.task = task; }
    }
    private final CodingAgentOperations root;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private boolean closed;

    SubagentManager(CodingAgentOperations root) {
        this.root = root;
        Entry main = new Entry(MAIN, "Main", "Main conversation");
        main.runtime = root;
        entries.put(MAIN, main);
        watch(main);
    }

    private void watch(Entry entry) {
        entry.runtime.subscribe(event -> {
            Event identified = new Event(entry.id, CodingAgentOperations.snapshotEvent(event));
            for (Consumer<Event> listener : listeners) {
                try { listener.accept(identified); } catch (RuntimeException ignored) { /* UI observers cannot fail a turn. */ }
            }
        });
    }

    public AutoCloseable subscribe(Consumer<Event> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    public synchronized List<Snapshot> list() {
        return entries.values().stream().map(this::snapshot).toList();
    }

    public synchronized Snapshot snapshot(String id) { return snapshot(require(id)); }

    private Snapshot snapshot(Entry entry) {
        var saved = entry.saved;
        var state = entry.runtime == null
                ? new CodingAgentOperations.AgentSnapshot(CodingAgentOperations.copyModel(entry.model),
                    entry.thinkingLevel,
                    false, false, true, saved == null ? List.of() : CodingAgentOperations.snapshotMessages(saved.messages),
                    saved == null ? null : entry.id, root.agentMode())
                : entry.runtime.state();
        Status status = state.streaming() || state.compacting() ? Status.RUNNING : entry.status;
        return new Snapshot(entry.id, entry.name, entry.task, status, entry.queue.size(), state,
                entry.runtime == null ? saved == null ? List.of() : CodingAgentOperations.snapshotMessages(saved.transcriptMessages)
                        : entry.runtime.transcript());
    }

    public synchronized boolean idle() {
        return entries.values().stream().allMatch(e -> e.worker == null && (e.runtime == null
                || !e.runtime.state().streaming() && !e.runtime.state().compacting()));
    }

    public synchronized boolean idle(String id) {
        Entry entry = require(id);
        return entry.worker == null && (entry.runtime == null
                || !entry.runtime.state().streaming() && !entry.runtime.state().compacting());
    }

    public synchronized String create(String task, String name) throws IOException {
        return create(task, name, null, null);
    }

    public synchronized String create(String task, String name, String model, ThinkingLevel level) throws IOException {
        if (closed) throw new IllegalStateException("Subagent manager is closed");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task must not be blank");
        String childName = name == null || name.isBlank() ? "Agent " + entries.size() : name.strip();
        Model childModel = root.resolveChildModel(model);
        ThinkingLevel requestedLevel = level == null ? root.state().thinkingLevel() : level;
        ThinkingLevel childLevel = CodingAgentOperations.clampThinkingLevel(childModel, requestedLevel);
        // The parent link is durable before any child provider call is made.
        SessionSnapshot saved = root.createChildSession(task, childName, childModel, childLevel);
        String id = saved == null ? CodingAgentOperations.uuidv7() : saved.id;
        Entry child = new Entry(id, childName, task);
        child.model = childModel;
        child.thinkingLevel = childLevel;
        child.saved = saved;
        entries.put(id, child);
        return id;
    }

    public synchronized CompletableFuture<Answer> submit(String id, String prompt) {
        if (closed) throw new IllegalStateException("Subagent manager is closed");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        return enqueue(id, prompt);
    }

    /** Compaction occupies the same mailbox, so new chats queue behind it. */
    public synchronized CompletableFuture<Answer> compact(String id) {
        return compact(id, null);
    }

    public synchronized CompletableFuture<Answer> compact(String id, String instructions) {
        if (closed) throw new IllegalStateException("Subagent manager is closed");
        if (!idle(id)) throw new IllegalStateException("Wait for the selected agent to be idle before compacting");
        return enqueue(id, null, instructions);
    }

    private CompletableFuture<Answer> enqueue(String id, String prompt) {
        return enqueue(id, prompt, null);
    }

    private CompletableFuture<Answer> enqueue(String id, String prompt, String compactionInstructions) {
        Entry entry = require(id);
        var result = new CompletableFuture<Answer>();
        entry.queue.addLast(new Request(prompt, compactionInstructions, result));
        if (entry.worker == null) {
            entry.worker = Thread.ofVirtual().name("agent-" + id).unstarted(() -> drain(entry));
            entry.worker.start();
        }
        return result;
    }

    private void drain(Entry entry) {
        while (true) {
            Request request;
            synchronized (this) {
                request = entry.queue.pollFirst();
                if (request == null || closed) { entry.worker = null; return; }
                entry.current = request;
                entry.status = Status.RUNNING;
            }
            Answer answer = null;
            Throwable failure = null;
            boolean drained;
            try {
                synchronized (this) {
                    if (request.result.isDone()) throw new CancellationException("Cancelled");
                    if (entry.runtime == null) {
                        entry.runtime = root.createChildRuntime(
                                entry.saved, entry.id, entry.model, entry.thinkingLevel);
                        watch(entry);
                    }
                }
                entry.runtime.recordLifecycle("RUNNING");
                if (request.result.isDone()) throw new CancellationException("Cancelled");
                if (request.prompt == null) {
                    var result = entry.runtime.compact(request.compactionInstructions);
                    answer = new Answer(entry.id, entry.name, Status.COMPLETED,
                            "Context compacted: " + result.tokensBefore + " -> " + result.estimatedTokensAfter + " tokens.");
                } else {
                    entry.runtime.mcpAwaitReady();
                    entry.runtime.syncMcpTools();
                    if (request.result.isDone()) throw new CancellationException("Cancelled");
                    List<Message> messages = entry.runtime.prompt(request.prompt);
                    AssistantMessage last = messages.stream().filter(AssistantMessage.class::isInstance)
                            .map(AssistantMessage.class::cast).reduce((a, b) -> b).orElseThrow();
                    if (last.stopReason == StopReason.ERROR || last.stopReason == StopReason.ABORTED)
                        throw new IllegalStateException(last.errorMessage == null ? last.stopReason.wire : last.errorMessage);
                    answer = new Answer(entry.id, entry.name, Status.COMPLETED, CodingAgentOperations.text(last));
                }
            } catch (Throwable error) {
                failure = error;
            }
            synchronized (this) {
                entry.status = request.result.isDone() ? Status.CANCELLED : failure == null ? Status.COMPLETED : Status.FAILED;
                try {
                    if (entry.runtime != null) entry.runtime.recordLifecycle(entry.status.name());
                } catch (IOException error) { failure = error; entry.status = Status.FAILED; }
                entry.current = null;
                drained = entry.queue.isEmpty() || closed;
                if (drained) entry.worker = null;
                // Interruption belongs to this request; subsequent accepted prompts can still run.
                Thread.interrupted();
            }
            if (failure == null) request.result.complete(answer);
            else request.result.completeExceptionally(new IllegalStateException("Agent " + entry.id + ": " + failure.getMessage(), failure));
            if (drained) return;
        }
    }

    public synchronized void cancel(String id) {
        Entry entry = require(id);
        if (MAIN.equals(id)) for (Entry child : List.copyOf(entries.values())) if (child != entry) cancelEntry(child);
        cancelEntry(entry);
    }

    private void cancelEntry(Entry entry) {
        var error = new CancellationException("Agent " + entry.id + " cancelled");
        List<Request> cancelled = new ArrayList<>(entry.queue);
        entry.queue.clear();
        if (entry.current != null) cancelled.add(entry.current);
        if (!cancelled.isEmpty() || entry.runtime != null && entry.runtime.state().streaming()) entry.status = Status.CANCELLED;
        if (entry.runtime != null) entry.runtime.abortLocal();
        if (entry.worker != null) entry.worker.interrupt();
        for (Request request : cancelled) request.result.completeExceptionally(error);
    }

    public synchronized CodingAgentOperations runtime(String id) throws IOException {
        Entry entry = require(id);
        if (entry.runtime == null) {
            entry.runtime = root.createChildRuntime(entry.saved, entry.id, entry.model, entry.thinkingLevel);
            watch(entry);
        }
        return entry.runtime;
    }

    synchronized void restore(List<SessionSnapshot> children) {
        if (!idle()) throw new IllegalStateException("Wait for all agents to be idle before resuming");
        for (Entry entry : entries.values()) if (entry.runtime != null && entry.runtime != root) entry.runtime.close();
        entries.keySet().removeIf(id -> !MAIN.equals(id));
        for (SessionSnapshot saved : children) {
            Entry entry = new Entry(saved.id, saved.name == null ? "Agent " + entries.size() : saved.name, saved.task);
            entry.saved = saved;
            try {
                entry.model = root.resolveChildModel(saved.provider + "/" + saved.model);
            } catch (IllegalArgumentException unavailable) {
                entry.model = root.resolveChildModel(null);
            }
            ThinkingLevel requestedLevel = saved.thinkingLevel == null
                    ? root.state().thinkingLevel()
                    : saved.thinkingLevel;
            entry.thinkingLevel = CodingAgentOperations.clampThinkingLevel(entry.model, requestedLevel);
            try { entry.status = Status.valueOf(saved.lifecycle); } catch (RuntimeException ignored) { entry.status = Status.IDLE; }
            if (entry.status == Status.RUNNING) entry.status = Status.INTERRUPTED;
            entries.put(entry.id, entry);
        }
    }

    private Entry require(String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown child agent: " + id);
        return entry;
    }

    AgentTool tool() {
        var task = text("task", "Task brief for the child. Include the context it needs; parent history is not shared.");
        var name = optionalText("name", "Optional display name for a new child", null);
        var id = optionalText("agent_id", "Reuse a child ID returned by this tool. Omit or leave blank to create a new child.", null);
        var model = optionalText("model", "Model for a new child, as a model ID from the current provider or provider/model. Omit to inherit the parent's model.", null);
        var thinkingLevel = optionalText("thinking_level", "Thinking level for a new child. Omit to inherit the parent's level.", null);
        var baseParameters = new ToolParameters(task, name, id, model, thinkingLevel);
        var schema = baseParameters.schema();
        var properties = (com.fasterxml.jackson.databind.node.ObjectNode) schema.get("properties");
        var levelSchema = (com.fasterxml.jackson.databind.node.ObjectNode) properties.get("thinking_level");
        var levels = levelSchema.putArray("enum");
        for (ThinkingLevel level : ThinkingLevel.values()) levels.add(level.wire);
        var parameters = baseParameters.withSchema(schema);
        return new ToolDefinition<SubagentManager>("subagent",
                "Delegate a task to a separate conversation and wait for its final answer. New children may use a selected model and thinking level. Children share the workspace and cannot delegate further.",
                parameters, (manager, args, invocation) -> {
                    String child = args.get(id);
                    if (MAIN.equals(child)) throw new IllegalArgumentException("agent_id must identify a child");
                    String requestedModel = args.get(model);
                    String requestedThinking = args.get(thinkingLevel);
                    if (child == null || child.isBlank()) {
                        child = manager.create(args.get(task), args.get(name), requestedModel,
                                parseThinkingLevel(requestedThinking));
                    } else if ((requestedModel != null && !requestedModel.isBlank())
                            || (requestedThinking != null && !requestedThinking.isBlank())) {
                        throw new IllegalArgumentException(
                                "model and thinking_level can only be selected when creating a new child");
                    }
                    CompletableFuture<Answer> result = manager.submit(child, args.get(task));
                    String childId = child;
                    root.onAbort(invocation.signal, () -> manager.cancel(childId));
                    try {
                        Answer answer = result.get();
                        CodingAgentOperations.AgentSnapshot state = manager.snapshot(answer.agentId).state();
                        var data = CodingAgentOperations.jsonObject().put("agent_id", answer.agentId)
                                .put("name", answer.name).put("status", answer.status.name().toLowerCase(Locale.ROOT))
                                .put("model", state.model().provider + "/" + state.model().id)
                                .put("thinking_level", state.thinkingLevel().wire)
                                .put("final_answer", answer.finalAnswer);
                        return new AgentTool.ToolResult(List.of(new TextContent(data.toString(), null)), data, false);
                    } catch (InterruptedException error) {
                        manager.cancel(childId);
                        throw new IllegalStateException("Agent " + childId + " cancelled", error);
                    } catch (ExecutionException | CancellationException error) {
                        throw new IllegalStateException("Agent " + childId + ": " + error.getMessage(), error);
                    }
                }, args -> args.path("task").asText()).bind(this);
    }

    private static ThinkingLevel parseThinkingLevel(String value) {
        if (value == null || value.isBlank()) return null;
        String requested = value.strip();
        for (ThinkingLevel level : ThinkingLevel.values()) {
            if (level.wire.equals(requested)) return level;
        }
        throw new IllegalArgumentException("Unknown thinking level: " + requested);
    }

    @Override public void close() {
        List<Entry> closing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = new ArrayList<>(entries.values());
            for (Entry entry : closing) cancelEntry(entry);
        }
        for (Entry entry : closing) {
            Thread worker = entry.worker;
            if (worker != null && worker != Thread.currentThread()) {
                try { worker.join(2000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if (entry.runtime != null && entry.runtime != root) entry.runtime.close();
        }
        listeners.clear();
    }
}
