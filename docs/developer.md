# Claudia developer guide

## The main loop

Three loops sit inside each other, from the outside in:

1. The **input loop** in `ClaudiaCli` reads what you type and queues prompts.
2. Each agent has a **mailbox loop** in `SubagentManager.drain`, which runs that
   agent's queued prompts one at a time.
3. The **agent loop** in `ClaudiaOperations.runPrompt` runs one prompt. It
   alternates between calling the model and running tools until the model
   answers without calling a tool.

### 1. Input loop: `ClaudiaCli`

The interactive shell runs this loop on the main thread:

```java
while (true) {
    String input = readLine("\n> ", slashCommands());
    drainShellEvents();
    if (input == null) return 0;                       // EOF / Ctrl-D
    if (input.isBlank()) continue;
    if (input.startsWith("/")) {                       // slash command
        if (dispatchSlashCommand(input)) return 0;
        continue;
    }
    ...
    runtime.subagents().submit(selectedAgent, input)   // queue the prompt
           .whenComplete(...);
}
```

- Slash commands run here, holding the editor lock.
- Everything else goes to the selected agent's queue (Main or a child) with
  `SubagentManager.submit`. The loop **does not wait** for the result, so you can
  keep typing while an agent works. If the agent is busy, the prompt waits in
  the queue and the shell prints `Prompt queued for …`.
- A `statusTicker` runs every 50 ms. It calls `drainShellEvents()`, which applies
  queued `AgentEvent`s to the transcript, and repaints the status bar. When a
  prompt finishes, its `whenComplete` callback adds a notice to
  `shellNotifications`, and the next drain runs it.

RPC mode (`--mode rpc`) uses a different input loop: it reads JSONL commands
from stdin. It drives the same runtime and agent loop.

### 2. Mailbox loop: `SubagentManager.drain`

Every agent, Main included, has an entry with a queue of `Request`s. `enqueue`
starts a virtual thread named `agent-<id>` when the queue goes from empty to
non-empty. That thread then runs:

```
loop:
    take the next request (exit when the queue is empty or the manager is closed)
    mark the agent RUNNING
    create the child ClaudiaOperations runtime the first time it is needed
    if it is a compaction request -> runtime.compact(...)
    else:
        runtime.mcpAwaitReady(); runtime.syncMcpTools()
        messages = runtime.prompt(text)        // the agent loop
        answer   = text of the last assistant message
    mark the agent COMPLETED / FAILED / CANCELLED and record its lifecycle
    complete the request's CompletableFuture
```

One thread per agent means prompts for the same agent run strictly in order,
while different agents run in parallel. The `subagent` tool uses this same path.
It submits a task to a child's queue and blocks on the future until the child
has an answer.

### 3. Agent loop: `ClaudiaOperations.runPrompt`

`prompt(text)` calls `runPrompt(text)`, which runs one user prompt to
completion. It returns only the messages created during that call.

**Setup**

1. While holding the group lock, it checks that the agent is idle, creates a new
   `AbortSignal` (Escape fires it) and sets `isStreaming = true`.
2. **Auto-compaction.** If auto-compaction is on and the estimated history size
   (about one token per 4 characters) is above
   `contextWindow - compactionReserveTokens`, it summarises the history first
   with `performCompaction`.
3. It emits `AgentStart` and `TurnStart`. Then it adds the user message to
   `messages` and passes it to `acceptMessage`, which records it in the session
   journal. It also emits `MessageStart`/`MessageEnd` for it.

**Turn loop: `while (true)`**

Each iteration is one *turn*: one model response, plus any tools that response
calls.

1. **Call the model with retry.** `retryAssistantCall` wraps a callable that:
   - builds a `Context`. Its system prompt is the base system prompt plus the
     current `AgentMode` instructions (Build or Plan). Its messages are the
     whole history, except assistant messages that ended in `ERROR` or
     `ABORTED`.
   - adds every bound tool, with its name, description and JSON schema. The
     tools are the built-in local tools, the debugger tools, the `subagent` tool
     (Main only) and any connected MCP tools.
   - opens a provider stream with the abort signal, thinking level and API key,
     then handles the events as they arrive:
     - `Start`: adds the partial assistant message to `messages` and emits
       `MessageStart`.
     - text, thinking and tool-call deltas: emits `MessageUpdate`, which the UI
       renders as it streams.
     - `Done` or `Error`: records the final message.
   - replaces the partial message with the final one and emits `MessageEnd`.

   If a response ends in a temporary error, `retryAssistantCall` retries it with
   exponential backoff (`baseDelayMs * 2^(attempt-1)`), up to the policy's
   `maxRetries`. Before each retry it removes the failed attempt from
   `messages`. Aborts are never retried; aborting during the backoff wait ends
   the call. Retries are reported with `AutoRetryStart`/`AutoRetryEnd`.

2. **Stop on failure.** If `stopReason` is `ERROR` or `ABORTED`, it emits
   `TurnEnd` and leaves the loop.

3. **Run tool calls one at a time, in order.** For each `ToolCall` in the
   response:
   - If the abort signal has fired, it skips the remaining calls.
   - It emits `ToolExecutionStart`, looks up the tool by name and calls
     `executeTool`. Partial results are sent as `ToolExecutionUpdate`.
   - An unknown tool name or a thrown exception becomes an error `ToolResult`
     that goes back to the model. It never ends the loop.
   - It emits `ToolExecutionEnd`, then adds a `ToolResultMessage` to `messages`,
     passes it to `acceptMessage`, and emits `MessageStart`/`MessageEnd`.

4. **Continue or finish.** It emits `TurnEnd(response, results)`. If no tools
   ran, the model has answered and the loop ends. Otherwise it emits `TurnStart`
   and goes round again, so the model sees the tool results.

**Cleanup (`finally`)**

It clears `isStreaming`, the streaming message, the pending tool calls and the
abort signal. Then it emits `AgentEnd(newMessages)`.

### Events, not shared state

The loop never touches the terminal. It reports everything through
`AgentEvent`s, which `emit()` sends to every registered listener. The
interactive shell, JSON mode and RPC mode are all listeners. That is why the
same `ClaudiaOperations` runtime can run without a terminal.

```
you type ─▶ ClaudiaCli input loop ─▶ SubagentManager queue ─▶ agent-<id> thread
                                                               │
                                                               ▼
                                     ┌──────────── runPrompt ────────────┐
                                     │ stream model response             │
                                     │   └─ tool calls? ── no ──▶ done   │
                                     │        │ yes                      │
                                     │   run each tool, add results ─┐   │
                                     │   ◀───────────────────────────┘   │
                                     └───────────────────────────────────┘
                                                   │ AgentEvents
                                                   ▼
                                   transcript / status bar / JSON / RPC
```

## Tools

The model sees these tools on every request. Built-in tools are defined in
[`LocalTools`](../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java)
and [`DebugTools`](../src/main/java/com/quaxt/claudia/debug/DebugTools.java),
and the `subagent` tool in
[`SubagentManager`](../src/main/java/com/quaxt/claudia/SubagentManager.java).
Each definition holds the tool's typed parameters, its handler and the one-line
summary the UI shows for a call. The parameter declarations generate the JSON
schema sent to the model, and arguments are validated against it before the tool
runs.

Tool output is limited in size. File and process tools cut text off at 2,000
lines or 50 KB. Debugger results are paged with `limit`/`cursor`.

### Files

| Tool | Purpose |
| --- | --- |
| [`read`](tools/read.md) | Read a text file, or an entry inside a jar/zip archive |
| [`write`](tools/write.md) | Create or overwrite a text file |
| [`edit`](tools/edit.md) | Replace exact, unique blocks of text in a file |
| [`ls`](tools/ls.md) | List a directory |
| [`find`](tools/find.md) | Find files by glob pattern |
| [`grep`](tools/grep.md) | Search file contents with a regular expression |

### Processes

| Tool | Purpose |
| --- | --- |
| [`shell`](tools/shell.md) | Run a bash (or PowerShell) command as a session that can be polled |
| [`shell_input`](tools/shell_input.md) | Poll a running session, send stdin to it, or terminate it |
| [`run_process`](tools/run_process.md) | Run an executable directly with exact arguments |
| [`preflight_posix`](tools/preflight_posix.md) | Check a Windows MSYS2/Git Bash build environment |

### Planning and collaboration

| Tool | Purpose |
| --- | --- |
| [`question`](tools/question.md) | Ask the user a question and wait for the answer |
| [`task_state`](tools/task_state.md) | Track tasks, findings and constraints for each agent, across turns |
| [`subagent`](tools/subagent.md) | Delegate a task to a child agent (Main only) |

### Java debugger

These tools are available only in the JVM CLI (`java -jar claudia.jar`). The
native executable cannot use JDI. Start with
[shared concepts](tools/debugger.md) (sessions, stops, waiting, paging,
idempotency and redaction).

| Tool | Purpose |
| --- | --- |
| [`debug_launch`](tools/debug_launch.md) | Launch a main class or a single test under the debugger |
| [`debug_attach`](tools/debug_attach.md) | Attach to a running JDWP-enabled JVM |
| [`debug_sessions`](tools/debug_sessions.md) | List debug sessions |
| [`debug_status`](tools/debug_status.md) | Show a session's full state and current stop |
| [`debug_detach`](tools/debug_detach.md) | Detach from a session, or terminate its target |
| [`debug_breakpoints`](tools/debug_breakpoints.md) | List, add, update, enable, disable or remove breakpoints |
| [`debug_continue`](tools/debug_continue.md) | Resume and wait for the next stop or exit |
| [`debug_step`](tools/debug_step.md) | Step into, over or out |
| [`debug_run_to`](tools/debug_run_to.md) | Run to a source line |
| [`debug_pause`](tools/debug_pause.md) | Suspend a running target |
| [`debug_wait`](tools/debug_wait.md) | Wait for an event without changing execution |
| [`debug_events`](tools/debug_events.md) | Page through debugger events |
| [`debug_output`](tools/debug_output.md) | Page through the target's captured stdout/stderr |
| [`debug_threads`](tools/debug_threads.md) | List threads at a stop |
| [`debug_stack`](tools/debug_stack.md) | List a thread's stack frames |
| [`debug_variables`](tools/debug_variables.md) | Show the variables in a frame |
| [`debug_object`](tools/debug_object.md) | Expand an object reference |
| [`debug_source`](tools/debug_source.md) | Show source around a frame or line |
| [`debug_exception`](tools/debug_exception.md) | Inspect the exception at a stop |
| [`debug_evaluate`](tools/debug_evaluate.md) | Evaluate read-only expressions in a frame |

### MCP tools

Tools from MCP servers are added to the same list as `<server>_<tool>`. The
built-in [code-lens](../code-lens/README.md) server provides
`code-lens_query`, `code-lens_context` and `code-lens_sql`. Its own README
documents them.
