# Working with subagents

A subagent is a separate child conversation for a delegated task. Main receives
its final answer rather than its reasoning, searches, intermediate output or
tool results. Give the child a self-contained brief: it does not see Main's
conversation history.

See the [`subagent` tool reference](tools/subagent.md) for parameters and result
fields, [sessions](sessions.md) for continuation and branching, and
[planning](planning.md) for the shared Plan/Build workflow.

## Delegate and follow up

Ask Main to delegate a bounded investigation, review or implementation task.
Include the relevant paths, constraints, expected deliverable and verification
requirements. For example:

> Delegate a review of the cache invalidation paths. Do not edit files. Return
> suspected defects with source locations and suggested tests.

A new child inherits Main's model and thinking level unless you select another
model/level during creation. A model may be a current-provider ID or
`provider/model`; inherited thinking is clamped to the child's supported levels.
Use the returned `agent_id` to delegate another task to that same conversation.
Reuse retains its original model and thinking level; those selections cannot be
changed through a follow-up `subagent` call.

Main waits for that particular delegated request to finish. The result identifies
the child, status, model and thinking level and includes its final answer.
Direct chats with the child are separate requests and do not replace the answer
returned to an earlier delegation. Children cannot delegate further.

## Shared workspace, independent resources

Children inherit the workspace, instructions and effective local/MCP
configuration. All agents share the same files: divide file ownership or
coordinate edits explicitly to avoid conflicting changes. Separate conversation
histories do not provide filesystem isolation.

Each child owns its conversation, [task state](tools/task_state.md), shell
processes, provider state and MCP connections. Main and children share one
[Plan/Build mode](planning.md); a child cannot switch independently. Questions
identify the requesting agent, so answer the actual request rather than sending
an ordinary queued chat message.

## Navigate and queue work

Use `/subagents` to search and switch between Main and children in creation
order. The selected agent supplies the transcript, streaming output, activity,
model/context display and `/details` (Ctrl-O) inspector. Switching views does not
stop background agents.

You can keep typing while an agent works. Submitted prompts queue in order for
the selected agent; each agent processes its own queue serially, while different
agents can run concurrently. A question blocks its requesting turn, not other
agents. Already queued prompts are not question answers.

- **Escape** closes an open selector first. Otherwise it cancels the selected
  agent and clears its queued prompts.
- Cancelling **Main** also cancels all children. Cancelling a delegated child
  returns a tool error identifying that child to Main.
- `/compact` targets the selected idle conversation, not necessarily Main.
- Session, workspace, mode, provider/model, settings and MCP changes require
  Main selected and the entire group idle, including queued work and questions.
- Exiting closes the entire group, regardless of the selected view.

## Persistence and cleanup

With session persistence enabled, each child has its own JSONL file, linked to
Main before its work starts. Accepted conversation steps are saved incrementally;
compaction keeps the full transcript and a separate continuation checkpoint.

`/resume` lists Main sessions and restores their linked children. Children that
were running when the process ended appear as **interrupted** and do not restart
on their own. Completed or interrupted children remain available for explicit
chat or further delegation. Child runtimes are restored lazily when needed;
live processes are not resumed.

`--no-session` provides the same delegation and navigation in memory without
saving child files. Clearing or leaving a session releases its child runtimes.
`/fork` creates a new Main without copying child ownership. `/cd` also releases
workspace-bound children instead of migrating them; their saved files remain.

## Headless use

Delegation works in print, JSON and RPC modes too. One-shot print usage closes
the group when the response ends; use the interactive CLI or a persistent RPC
connection for follow-up exchanges.

Ordinary RPC conversation events describe Main, not child transcripts. Question
events are the exception: they identify the requesting agent, including children.
There are no RPC commands for switching the selected conversation. See
[RPC questions](planning.md#rpc-mode-and-questions).

## Implementation and verification pointers

- [SubagentManager](../src/main/java/com/quaxt/claudia/SubagentManager.java): child creation, independent mailboxes, cancellation and lazy restoration.
- [ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java): agent selector, selected transcript and command guards.
- [SubagentManagerTest](../src/test/java/com/quaxt/claudia/SubagentManagerTest.java): isolated histories/resources, request-specific answers, queue cancellation, parent links and interrupted restoration.
- [SubagentHeadlessTest](../src/test/java/com/quaxt/claudia/SubagentHeadlessTest.java) and [QuestionHeadlessTest](../src/test/java/com/quaxt/claudia/QuestionHeadlessTest.java): headless delegation and child questions over RPC.
