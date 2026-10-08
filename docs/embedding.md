# Embedding the runtime

`ClaudiaOperations` can execute prompts without the terminal CLI. It owns agent
execution, provider configuration, tools, instructions and persistence.
`ClaudiaCli` supplies command-line modes and presentation; it reads runtime
snapshots and subscribes to `AgentEvent` updates rather than implementing a
second agent loop.

## Ownership and cleanup

Each runtime owns its conversation, shell sessions, MCP connections and debugger
manager. A root runtime also owns its subagent group and question broker.
`ShellSessionManager` manages subprocess I/O independently of the model loop.

Use the runtime in try-with-resources, or otherwise ensure `close()` is called:

```java
try (ClaudiaOperations runtime = new ClaudiaOperations()) {
    // Configure a provider/model, workspace and instructions before prompting.
}
```

Closing cancels active work, closes child runtimes, releases MCP connections,
shell sessions and debugger resources, and clears listeners. The CLI creates
and closes its own runtime. Processes are not restored from saved sessions.

Separate runtimes can use different `ClaudiaPaths` through `applicationPaths(...)`
without sharing credentials or conversation state in memory. Choose distinct
storage locations when on-disk state must also be isolated. This does not
isolate access to the filesystem: runtimes in the same workspace share files.

## Configuration and execution

`configureAgent(...)` starts a fresh conversation with a model, workspace,
system-prompt text, API key and thinking level. An overload accepts an explicit
`Provider`, including an in-process provider for embedding or tests. Built-in
workspace tools and the root's subagent tool are configured with the runtime.

Configuration resets conversation/task state and disables session recording;
recording is an explicit separate step. `setSessionRecording(...)` requires a
configured session recorder and a checkpoint-failure callback. See
[sessions](sessions.md) for journal and compaction semantics.

`prompt(text)` is synchronous: it runs the agent loop and returns the messages
created during that call, not the entire history. Subscribe to events for live
text, reasoning, tool execution and lifecycle updates; use `state()` for a
snapshot. The runtime itself never draws a terminal.

For queued asynchronous work, the `SubagentManager` mailbox serializes requests
for each agent while allowing different agents to work in parallel. Do not call
`prompt` concurrently on the same runtime. Configuration changes require an
idle agent or, where applicable, an idle group. `setModel(...)` preserves the
conversation, tools and session recorder; `configureAgent(...)` does not.
Thinking-level changes are clamped to the selected model's supported levels.

## Tools

Built-in tool definitions in `LocalTools` keep metadata, typed parameters,
execution handlers and call descriptions together. Parameter declarations
generate the model-facing schema and validate arguments before execution.
Adding a definition does not require a new tool-kind enum or parallel
execution/presentation switch branches.

`ToolRegistryTest` exercises these contracts, including custom tools bound to
independent contexts. Debugger definitions live in `DebugTools`; delegation is
defined in `SubagentManager`. MCP tools join the same model-facing tool list.

This is a source-level integration guide, not a promise of a stable external
library API. For subprocess integration, [JSONL RPC](rpc.md) provides the CLI's
automation interface.

## See also

- [Developer guide and loop architecture](developer.md)
- [Agent instructions](instructions.md)
- [MCP configuration](mcp.md)
- [Subagent lifecycle](subagents.md)
- [Process tools](tools/shell.md)
