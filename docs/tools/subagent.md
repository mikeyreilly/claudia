# `subagent`

Hands a task to a separate child conversation and waits for its final answer.

Defined in
[`SubagentManager.tool()`](../../src/main/java/com/quaxt/claudia/SubagentManager.java).
Only Main has this tool, so children cannot delegate further.

## Parameters

| Name | Type | Description |
| --- | --- | --- |
| `task` | string | Instructions for the child. Include all the context it needs, because the child does not see the parent's history. |
| `name` | string, optional | Display name for a new child. |
| `agent_id` | string, optional | ID of an existing child to give this task to. Leave it out to create a new child. |
| `model` | string, optional | Model for a new child: a model ID from the current provider, or `provider/model`. Defaults to Main's model. |
| `thinking_level` | enum, optional | Thinking level for a new child: `off`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`. Defaults to Main's level, reduced to the highest level the model supports. |

`model` and `thinking_level` can only be set when creating a child. Passing
them with an `agent_id` is an error, and so is `agent_id: "main"`.

## Behaviour

1. If no `agent_id` is given, a new child is created. It gets its own
   `ClaudiaOperations` runtime, using the same workspace, instructions and
   local/MCP configuration as Main.
2. The task goes onto the child's queue with `SubagentManager.submit` and runs
   in the child's own agent loop (see the
   [main loop](../developer.md#the-main-loop)).
3. Main's tool call blocks until the child finishes. If Main's turn is aborted,
   the child is cancelled too.
4. The result is JSON with `agent_id`, `name`, `status`, `model`,
   `thinking_level` and `final_answer`, which is the text of the child's last
   assistant message.

The child's reasoning, tool calls and intermediate output stay in its own
transcript. You can open it with `/subagents`. Agents share the workspace's
files, but each owns its own shell processes, MCP connections, conversation and
[`task_state`](task_state.md). Each child is saved in its own session file,
linked to its parent.

## UI summary

The task text.
