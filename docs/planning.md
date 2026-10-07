# Planning, Build mode and clarification

Plan mode investigates a change and develops an implementation plan before
editing code. Build mode carries out implementation requests. They use the same
model, thinking level and tools; the difference is the instructions supplied to
the model, **not enforced tool permissions or a filesystem sandbox**.

See also [sessions](sessions.md), [subagents](subagents.md), the
[`question` reference](tools/question.md) and
[`task_state` reference](tools/task_state.md).

## Interactive workflow

1. Select Main and wait for the entire group to be idle, including queued
   prompts and outstanding questions.
2. Use `/plan`, or Shift-Tab at the main chat prompt, to select Plan mode. The
   status bar displays the current mode.
3. Send an investigation request. The agent should explore the workspace first,
   clarify material ambiguity that source/docs cannot resolve, and present an
   actionable Markdown plan in chat.
4. Review the goal, affected code, ordered changes, decisions, assumptions and
   verification steps. Resolve open questions explicitly.
5. Use `/build` (or Shift-Tab) to switch back, then send an explicit request such
   as “Implement the reviewed plan.”

Switching modes preserves the conversation and starts no work. Chat approval,
an answer to a question, or a request to implement does **not** switch modes.
The user must switch through the application and then request implementation.
Main and all its children share one mode; a child cannot switch independently.

### Scope of planning

Plan instructions allow targeted reads, searches and analysis. Diagnostic tests
and builds are allowed when useful for planning and producing only disposable
build output or caches.

Source/configuration edits, creating plan files, automatic fixes, installs,
commits, deployments and changes to external systems are outside this workflow.
This applies equally to shell commands, MCP tools and delegated work, not only
the write/edit tools. Because these are model instructions rather than executor
restrictions, Plan mode should not be treated as a security boundary.

Use per-agent [task state](tools/task_state.md) for substantial work to track
coarse tasks, findings and constraints. Check it after compaction or resume and
before completion. Compaction retains planning information in its summary;
task state and the selected mode remain outside the summarized history.

## Clarification in the terminal

The `question` tool is available in both Plan and Build, to Main and children.
It asks one question at a time with optional suggested answers. The terminal
shows who is asking; choose a suggestion or type a custom answer, then press
Enter. Escape declines without supplying an answer.

Questions wait while a selector or auxiliary prompt is open, preserving your
chat draft and cursor. The requesting agent waits, while other agents may
continue. Queued chat messages are never consumed as question answers.

A declined or unavailable result is not an answer. The agent should retain the
unresolved issue and present it in its response, rather than repeatedly asking
or assuming approval. Answering a question never grants permission to switch
from Plan to Build.

## Persistence and command-line use

Mode is saved with each session and restored on resume. Clear, fork, workspace
changes and model changes retain the current selection. A new process defaults
to Build independently of previous sessions; `--agent-mode plan` selects Plan
for interactive, print, JSON or RPC usage:

```sh
java -jar target/claudia.jar --agent-mode plan
java -jar target/claudia.jar --agent-mode plan --print "Plan the cache refactor"
```

Completed question results and answers are saved in the ordinary tool transcript.
Interrupted questions are not automatically reopened on resume. With
`--no-session`, mode and answers stay in memory only.

Print/JSON mode cannot collect answers: the question tool immediately returns
`unavailable` and tells the model to present the unresolved question in its
response. It does not wait for stdin; use interactive or persistent RPC mode
when clarification must be answered during execution.

## RPC mode and questions

`--mode rpc` reads JSONL commands on stdin and emits JSONL responses/events on
stdout. `get_state` includes `agentMode` (`build` or `plan`) and
`pendingQuestions`. Pending requests and `question_requested` events include
`questionId`, `agentId`, `question` and options with labels/descriptions.
Question events include delegated children even though ordinary conversation
events remain scoped to Main.

Send an answer (including the selected label when choosing a suggestion), or
explicitly decline, using the matching `questionId`:

```json
{"id":"mode-1","type":"set_agent_mode","agentMode":"plan"}
{"id":"reply-1","type":"answer_question","questionId":"<request-id>","answer":"Use the smaller scope"}
{"id":"reply-2","type":"answer_question","questionId":"<another-request-id>","decline":true}
```

`question_resolved` identifies the same request and reports `answered`,
`declined` or `unavailable`, with answer text when supplied. Duplicate or stale
replies fail without changing the conversation.

Prompt and compaction work leave the input loop responsive to state queries,
question replies and aborts. Prompts queue in order. Mode, model, session and
settings changes require the whole group idle, so wait for prompt completion
before issuing them. Switching to Build still requires a subsequent `prompt`
command to start implementation.

EOF makes pending and future questions unavailable, then drains already accepted
prompts before exiting. For a live clarification exchange, keep stdin open;
a pipe that immediately ends cannot wait for a user answer.

## Implementation and verification pointers

- [AgentMode](../src/main/java/com/quaxt/claudia/agent/AgentMode.java): current Plan/Build instructions and the explicitly advisory boundary.
- [ClaudiaOperations](../src/main/java/com/quaxt/claudia/ClaudiaOperations.java) and [ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java): group-idle guards, session mode persistence, CLI flags and RPC commands.
- [PlanModeTest](../src/test/java/com/quaxt/claudia/PlanModeTest.java): mode-only switching, unchanged tools, inheritance, compaction, resume and explicit questions.
- [QuestionComponentTest](../src/test/java/com/quaxt/claudia/QuestionComponentTest.java) and [QuestionHeadlessTest](../src/test/java/com/quaxt/claudia/QuestionHeadlessTest.java): terminal answers/declines, RPC child questions, stale replies, EOF and unavailable print-mode questions.
