package com.quaxt.claudia.agent;

import com.quaxt.claudia.ai.util.AbortSignal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.quaxt.claudia.ClaudiaOperations.uuidv7;

/** One group's pending questions. Hosts opt in to interaction and supply explicit replies. */
public final class QuestionBroker implements AutoCloseable {
    public record Option(String label, String description) {
        public Option {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("Option label must not be blank");
            description = description == null ? "" : description;
        }
    }
    public record Request(String questionId, String agentId, String question, List<Option> options) {
        public Request { options = List.copyOf(options); }
    }
    public record Answer(String status, String answer) {
        public static Answer unavailable() { return new Answer("unavailable", null); }
        public static Answer declined() { return new Answer("declined", null); }
    }
    public record Event(String type, Request request, Answer answer) {}
    private record Pending(Request request, CompletableFuture<Answer> future) {}
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Deque<Event> events = new ArrayDeque<>();
    private boolean dispatching;
    private boolean interactive;
    private boolean closed;

    public AutoCloseable subscribe(Consumer<Event> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    /** Disabling interaction releases existing questions; useful when an RPC input stream reaches EOF. */
    public void setInteractive(boolean enabled) {
        List<Pending> released;
        synchronized (this) {
            if (closed && enabled) throw new IllegalStateException("Question broker is closed");
            interactive = enabled;
            released = enabled ? List.of() : takeAll();
        }
        released.forEach(item -> complete(item, Answer.unavailable()));
    }

    public synchronized List<Request> pending() { return pending.values().stream().map(Pending::request).toList(); }
    public synchronized boolean contains(String id) { return pending.containsKey(id); }

    public Answer ask(String agentId, String question, List<Option> options, AbortSignal signal) throws InterruptedException {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        Objects.requireNonNull(options);
        Objects.requireNonNull(signal);
        Pending item;
        synchronized (this) {
            if (closed || !interactive) return Answer.unavailable();
            item = new Pending(new Request(uuidv7(), agentId, question, options), new CompletableFuture<>());
            pending.put(item.request.questionId, item);
            events.addLast(new Event("question_requested", item.request, null));
        }
        dispatchEvents();
        try {
            // Polling only the cancellation token avoids retaining a listener after the question ends.
            while (true) {
                synchronized (signal) {
                    if (signal.aborted) throw new InterruptedException("Question cancelled");
                }
                try { return item.future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
                catch (ExecutionException error) { throw new IllegalStateException(error.getCause()); }
            }
        } finally {
            Pending removed;
            synchronized (this) { removed = pending.remove(item.request.questionId); }
            if (removed != null) complete(removed, Answer.declined());
        }
    }

    public void answer(String questionId, String answer) {
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("answer must not be blank");
        resolve(questionId, new Answer("answered", answer));
    }

    public void decline(String questionId) { resolve(questionId, Answer.declined()); }

    private void resolve(String id, Answer answer) {
        Pending item;
        synchronized (this) { item = pending.remove(id); }
        if (item == null) throw new IllegalArgumentException("Unknown or already resolved question: " + id);
        complete(item, answer);
    }

    public void cancelAgent(String agentId) {
        List<Pending> released = new ArrayList<>();
        synchronized (this) {
            pending.values().removeIf(item -> {
                if (!item.request.agentId.equals(agentId)) return false;
                released.add(item); return true;
            });
        }
        released.forEach(item -> complete(item, Answer.declined()));
    }

    private List<Pending> takeAll() {
        List<Pending> items = List.copyOf(pending.values());
        pending.clear();
        return items;
    }

    private void complete(Pending item, Answer answer) {
        emit(new Event("question_resolved", item.request, answer));
        item.future.complete(answer);
    }

    private void emit(Event event) {
        synchronized (this) { events.addLast(event); }
        dispatchEvents();
    }

    /** Serialize observer delivery without holding the broker monitor during callbacks. */
    private void dispatchEvents() {
        synchronized (this) {
            if (dispatching) return;
            dispatching = true;
        }
        while (true) {
            Event event;
            synchronized (this) {
                event = events.pollFirst();
                if (event == null) { dispatching = false; return; }
            }
            for (Consumer<Event> listener : listeners) {
                try { listener.accept(event); } catch (RuntimeException ignored) { /* Observers cannot fail a turn. */ }
            }
        }
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        setInteractive(false);
        listeners.clear();
    }
}
