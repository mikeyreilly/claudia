package com.quaxt.agentic;

import java.util.ArrayList;
import java.util.List;

/** Provider-independent model/tool loop extracted from ClaudiaOperations.runPrompt.
 * Hosts own conversations, provider calls, tool registries and event presentation.
 * No filesystem, terminal, credential store, or application tools are installed.
 */
public final class AgentLoop {
    private AgentLoop() {}

    public interface Host<R, C, T> {
        R respond() throws InterruptedException;
        void acceptResponse(R response);
        boolean failed(R response);
        List<C> calls(R response);
        boolean cancelled();
        T execute(C call) throws Exception;
        T toolError(C call, Exception error);
        void acceptResult(C call, T result);
        void turnEnded(R response, List<T> results);
        void nextTurn();
    }

    /** One invocation; tool errors return to the model, provider/host failures propagate.
     * InterruptedException is terminal. A host must check cancellation in long I/O.
     */
    public static <R, C, T> R run(Host<R, C, T> host, int maxTurns, int maxCalls)
            throws InterruptedException {
        if (maxTurns < 1 || maxCalls < 1) throw new IllegalArgumentException("Positive limits required");
        int calls = 0;
        for (int turn = 0; turn < maxTurns; turn++) {
            R response = host.respond();
            host.acceptResponse(response);
            List<T> results = new ArrayList<>();
            if (!host.failed(response)) {
                for (C call : host.calls(response)) {
                    if (host.cancelled()) break;
                    if (++calls > maxCalls) throw new IllegalStateException("Agent tool limit reached");
                    T result;
                    try {
                        result = host.execute(call);
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Exception error) {
                        result = host.toolError(call, error);
                    }
                    host.acceptResult(call, result);
                    results.add(result);
                }
            }
            host.turnEnded(response, List.copyOf(results));
            if (host.failed(response) || results.isEmpty()) return response;
            host.nextTurn();
        }
        throw new IllegalStateException("Agent turn limit reached");
    }
}
