# JSONL RPC mode

`--mode rpc` accepts one JSON command per stdin line and emits JSONL responses
and agent events on stdout.

See also [build/run](build.md), [providers and credentials](providers.md),
[MCP servers](mcp.md), [sessions](sessions.md), [planning and questions](planning.md)
and [subagents](subagents.md).

## Start a connection

RPC requires an explicit model: `--model provider/model`, or `--provider`
together with an unqualified `--model`. A conflicting provider is rejected.
Configure credentials as described in the provider guide; `--api-key` overrides
environment-based credentials. Example (POSIX shell):

```sh
printf '%s\n' '{"id":"state-1","type":"get_state"}' |
  java -jar target/claudia.jar \
  --mode rpc --model anthropic/claude-haiku-4-5 --no-session
```

PowerShell equivalent:

```powershell
'{"id":"state-1","type":"get_state"}' |
  java -jar target/claudia.jar --mode rpc --model anthropic/claude-haiku-4-5 --no-session
```

Without `--no-session`, conversations are recorded in append-only JSONL files
under `~/.claudia/sessions`. Successful manual/automatic compactions save resume
boundaries while retaining the original transcript. With `--no-session`, state
remains in memory and `get_state.data.sessionId` is an empty string.

RPC loads configured/built-in [MCP](mcp.md) servers in the process workspace.
`--agent-mode plan` selects Plan initially; the default for a new process is
Build. RPC agent configuration starts with thinking off; there is no RPC command
for selecting a thinking level.

## Framing and responses

Each command is a JSON object with a string `type`. A string `id` is optional
but recommended for correlation; non-string IDs are treated as absent. This
protocol is **not JSON-RPC 2.0**: do not use `method`/`params` envelopes.

Responses have `type: "response"`, `command`, `success`, and the supplied string
`id` when present. Successful commands may have `data`; failures have `error`:

```json
{"id":"text-1","type":"get_last_assistant_text"}
```

Illustrative responses:

```json
{"type":"response","id":"text-1","command":"get_last_assistant_text","success":true,"data":{"text":null}}
{"type":"response","id":"bad-1","command":"unknown","success":false,"error":"Unsupported command: unknown"}
```

Malformed JSON or a non-object/missing string `type` produces a failed `parse`
response without an ID, and the input loop continues. Command-validation errors
produce a failure for that command. Read **every** output line and dispatch by
`type`: events and responses interleave, and completion order can differ from
submission order. Events do not carry the command's request ID.

## Commands

Fields below are top-level command fields, alongside `id` and `type`.

| `type` | Fields | Successful response |
| --- | --- | --- |
| `prompt` | Required nonblank string `message`. | Completion response, no `data`; answer text arrives in events or can be fetched afterward. |
| `abort` | None. | No `data`; requests cancellation, not a guarantee that all work has already settled. |
| `get_state` | None. | `data` with model, activity, session, mode and pending questions (below). |
| `get_available_models` | None. | `data.models`, the runtime model catalog. |
| `set_model` | Required nonblank strings `provider`, `modelId`. | Selected model object in `data`; resets the RPC conversation. |
| `get_messages` | None. | `data.messages`, serialized Main conversation messages. |
| `get_last_assistant_text` | None. | `data.text`, latest Main assistant text or `null` if none. |
| `new_session` | None. | `data.cancelled: false`; resets with the current model. |
| `compact` | Optional string `customInstructions`. | Completion response with serialized compaction result in `data`. |
| `set_auto_compaction` | Required boolean `enabled`. | No `data`. |
| `set_agent_mode` | Required `agentMode`: `build` or `plan`. | `data.agentMode`. |
| `answer_question` | Required nonblank `questionId`; nonblank `answer`, or `decline: true`. | No `data`; stale/duplicate replies fail. |

`get_state.data` contains `model`, `isStreaming`, `isCompacting`,
`autoCompactionEnabled`, `messageCount`, `sessionId`, `agentMode` and
`pendingQuestions`. Streaming/compacting flags describe Main; they are not a
complete child/queue activity listing.

**Model switching differs from the interactive UI:** current RPC `set_model`
reconfigures the agent and starts a fresh conversation/session, just like
`new_session`. It does not preserve the conversation as interactive `/models`
does. Neither command is a resume or fork operation. There are no RPC resume,
workspace-change, child-navigation, provider-login or MCP-toggle commands.

### Queueing, idle guards and EOF

Prompts queue in order for Main. Their response IDs complete when the submitted
work finishes, not when accepted. Compaction is also asynchronous and leaves the
input loop responsive to state queries, question answers and aborts.

`set_model`, `new_session`, `set_agent_mode` and `set_auto_compaction` require
the **whole group idle**, including queued work and outstanding questions. Wait
for outstanding prompt/compaction completion responses before requesting such
changes; they fail rather than implicitly cancelling active work. Changing mode
starts no work: follow it with a separate prompt.

Abort cancels Main and its children and clears queued prompts. Continue reading
completion responses/events so you know when cancellation has settled.

EOF makes pending and future questions unavailable, then drains already accepted
work before exiting. A pipe that immediately ends is useful for batch commands,
but cannot support an interactive question exchange. Keep stdin open for live
clarification and for shell-process exchanges across turns. On process exit,
runtime resources and running shell processes are closed; see
[shell processes](tools/shell.md).

## Example command sequence

For a persistent connection, send each line at the appropriate time. Wait for
the `plan-1` completion before switching mode; a question may need an answer
before that completion:

```json
{"id":"mode-1","type":"set_agent_mode","agentMode":"plan"}
{"id":"plan-1","type":"prompt","message":"Investigate the cache refactor and propose a plan; do not edit code."}
{"id":"state-2","type":"get_state"}
```

After reviewing the plan and explicitly deciding to implement:

```json
{"id":"mode-2","type":"set_agent_mode","agentMode":"build"}
{"id":"build-1","type":"prompt","message":"Implement the reviewed cache-refactor plan."}
```

After `build-1` finishes:

```json
{"id":"text-2","type":"get_last_assistant_text"}
{"id":"compact-1","type":"compact","customInstructions":"Retain verification results and remaining work."}
```

These are protocol examples, not a promise that arbitrary model/tool work will
succeed; check every completion's `success` and inspect assistant messages.

## Clarification questions

Each `pendingQuestions` entry and `question_requested` event contains
`questionId`, `agentId`, `question`, and `options` (label/description pairs).
Question events include delegated children even though ordinary conversation
events remain scoped to Main.

Reply using the exact request ID. Include the selected label in answer text when
choosing a suggestion, or explicitly decline:

```json
{"id":"reply-1","type":"answer_question","questionId":"<request-id>","answer":"Use the smaller scope"}
{"id":"reply-2","type":"answer_question","questionId":"<another-request-id>","decline":true}
```

Do not supply `answer` together with `decline: true`. `question_resolved`
identifies the same question and reports `status` (`answered`, `declined` or
`unavailable`), with `answer` when supplied. Duplicate or stale replies fail
without changing the conversation. Declining is not an answer or authorization
to change mode; see [planning](planning.md).

## Events

The current encoder emits these RPC events:

| Event `type` | Additional fields |
| --- | --- |
| `agent_start`, `turn_start` | None. |
| `agent_settled` | `messageCount` (new messages in this agent execution). |
| `turn_end` | `toolResultCount`. |
| `instruction_loaded` | `path`. |
| `message_start` | `role`. |
| `message_update` | `assistantMessageEvent` with `type: "text_delta"`, `delta`. |
| `message_end` | `role`; assistant messages also include `text`. |
| `tool_execution_start` | `toolCallId`, `toolName`, `arguments`. |
| `tool_execution_end` | `toolCallId`, `toolName`, `isError`. |
| `compaction_start` | `tokensBefore`. |
| `compaction_end` | `tokensBefore`, `estimatedTokensAfter`. |
| `auto_retry_start` | `attempt`, `maxAttempts`, `delayMs`, `error`. |
| `auto_retry_end` | `success`, `attempt`; optional final `error`. |
| `question_requested`, `question_resolved` | Question fields described above. |

For example, streamed answer text uses this shape:

```json
{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hello"}}
```

Only text deltas are forwarded as message updates. Reasoning deltas and tool
execution updates are not emitted, and tool-end events do not include full tool
results. Use `get_messages` for serialized conversation content. Ordinary events
and message queries describe Main, not child transcripts; delegated reasoning,
searches and tool results remain in the child conversation. There are no RPC
commands to navigate children. See [subagents](subagents.md).

Do not confuse RPC events with `--mode json --print`: the latter uses names
such as `text_delta`, `tool_start`, `tool_end` and `agent_end`, and cannot collect
question answers.

## Verification pointers

- [ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java): RPC dispatcher, reset behavior, response envelope, question/event encoding and EOF handling.
- [ClaudiaOperations](../src/main/java/com/quaxt/claudia/ClaudiaOperations.java) and [SubagentManager](../src/main/java/com/quaxt/claudia/SubagentManager.java): group-idle checks, queued prompts, cancellation and compaction.
- [QuestionHeadlessTest](../src/test/java/com/quaxt/claudia/QuestionHeadlessTest.java): child questions, replies/declines, stale answers, abort, EOF and responsive compaction.
- [SubagentHeadlessTest](../src/test/java/com/quaxt/claudia/SubagentHeadlessTest.java): RPC delegation without leaking child events.
