# Sessions and conversation history

Claudia normally records an append-only JSONL journal in
`~/.claudia/sessions`. Accepted messages and tool results are saved incrementally,
not just when a prompt finishes. `--no-session` keeps the conversation in memory
instead; it still supports compaction, task state, forks and subagents, but
`/resume` is unavailable.

See also [subagents](subagents.md), [planning](planning.md), the
[runtime developer guide](developer.md), and the
[`task_state` reference](tools/task_state.md).

## Interactive session operations

Select Main and wait for the whole agent group to be idle before changing
sessions, workspace, model/provider, settings or MCP configuration. Queued
prompts and outstanding questions count as work. `/compact` is different: it
only requires the selected conversation to be idle.

| Command | Effect |
| --- | --- |
| `/resume` | Search saved Main sessions for the current workspace and continue one. |
| `/clear` | Start a fresh unnamed conversation with the current model, workspace and mode. |
| `/fork` | Copy the current continuation context and task state into a new named Main session. |
| `/cd <directory>` | Rebuild workspace-bound tools and instructions without discarding Main's conversation. |
| `/compact` | Summarize the selected agent's context while retaining its full transcript. |

### Resume

The searchable selector excludes the active session and sessions with no
messages. Selecting a session restores its visible transcript, continuation
context, task state, saved mode and linked children. New messages append to the
same JSONL file. The latest saved model is restored if available; otherwise the
currently configured model is used as a fallback. A missing workspace prevents
resuming the session.

Previously running children become **interrupted**, not automatically restarted.
Completed children can be chatted with or delegated to again; see
[subagent persistence](subagents.md#persistence-and-cleanup).

### Clear and fork

`/clear` clears the visible transcript and task state and releases child runtimes.
It does not delete the old saved session. With persistence enabled, the fresh
conversation gets its own session file; with `--no-session`, it is memory-only.

`/fork` prompts for a name, initially `<current name> fork`, or `fork` when
unnamed. Submit a nonblank name to switch to the new session. The fork copies
Main's resumable messages and task state into independent state, including with
`--no-session`. It keeps the current model, workspace and mode, but does **not**
copy child ownership. After compaction, the copied model context is the checkpoint
and later messages, not an expansion of the pre-compaction transcript.

Changing models through `/models` is not a fresh session: conversation history,
task state, children and the session file remain. The latest model selection is
recorded for resume. Use `/clear` when you want a clean conversation instead.

### Workspace changes

`/cd` accepts absolute paths, paths relative to the current agent workspace, and
`~` for your home directory. The destination must exist and be a directory. It
does not change the parent terminal shell's directory.

A workspace change rebuilds local tools and instruction context and reconnects
MCP servers. Main's messages, task state, mode and session identity are preserved.
For a saved session, an append-only workspace-change entry makes it appear under
the new workspace in `/resume`; the JSONL file remains in the session store.
Workspace-bound children are released, not migrated. Their saved transcripts
remain in their original session files.

## Compaction and durable state

Manual `/compact` and automatic compaction summarize active model history to
free context space. Automatic compaction is enabled by default and runs before
a prompt when the history estimate exceeds the model's context window minus the
reserved tokens. The current default reserve is 16,384 tokens; the estimate is
roughly one token per four characters, not an exact provider token count.

Successful compactions append a resume boundary rather than rewriting the
journal. The full transcript remains inspectable, but resume sends only the
latest saved checkpoint plus subsequent messages to the model. Compaction does
not reset the selected mode or the separate per-agent task state.

For substantial work, use [task state](tools/task_state.md) to retain coarse
tasks, findings and constraints beyond summaries. Check it after compaction or
resume and before declaring completion; it is not a substitute for detailed
conversation context.

Saved conversations do not restore live subprocesses. Shell processes survive
ordinary chat turns but are stopped by cancellation/reset/exit and cannot be
recreated by resume. See the [shell lifecycle reference](tools/shell.md).

## Implementation and verification pointers

- [ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java): slash-command guards, resume selector, fork and workspace transitions.
- [ClaudiaOperations](../src/main/java/com/quaxt/claudia/ClaudiaOperations.java): session journal, workspace-change entries, compaction checkpoints and snapshot reconstruction.
- [ClaudiaCliTest](../src/test/java/com/quaxt/claudia/ClaudiaCliTest.java) and [ClaudiaOperationsTest](../src/test/java/com/quaxt/claudia/ClaudiaOperationsTest.java): workspace preservation, same-file continuation, forks and latest-checkpoint resume.
- [TaskStateTest](../src/test/java/com/quaxt/claudia/TaskStateTest.java): durable task state and independent forks.
