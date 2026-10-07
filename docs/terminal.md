# Terminal usage

Start with `claudia` (installed launcher) or `java -jar target/claudia.jar`.
Run `/login`, then `/models`, and submit a request with Enter. `/help` lists
commands and shortcuts; `/exit` quits. See [build and run](build.md),
[providers and credentials](providers.md), and [the developer guide](developer.md).

Claudia has no permissions system: agent tools can do anything your user
account can do. Use a sandbox for untrusted work.

## Terminal requirements

The JVM interactive shell supports Ghostty on macOS/Linux (x86_64 or aarch64)
and Windows consoles with virtual-terminal input/output, such as Windows
Terminal. Other xterm-compatible terminals may work but are not the tested
target. POSIX terminals use termios; Windows uses the Win32 console API.
Interactive mode requires both stdin and stdout to be a terminal/console;
redirected input or output causes an error.

`--print`, `--mode json`, and `--mode rpc` do not use the terminal. Use them
for headless operation or redirected streams. The runnable JAR's manifest
enables native access for the terminal layer; classpath launches should pass
`--enable-native-access=ALL-UNNAMED` to avoid the JDK warning.
GraalVM native interactive mode supports Linux and Apple silicon Macs, not
Windows or Intel Macs. Windows has no process-group suspend shortcut.

## Prompt editing

Typing `/` opens an alphabetized command panel. Continue typing to
prefix-filter; Up/Down selects and Enter inserts the command.
Pasted text retains its line breaks.

| Keys | Action |
| --- | --- |
| Enter | Submit; prompts queue for the selected agent if it is busy |
| Shift/Ctrl/Alt-Enter or Ctrl-J | Insert a newline (modified Enter depends on terminal reporting) |
| Ctrl-A / Ctrl-E, Home / End | Start / end of the current line |
| Alt-B / Alt-F, Ctrl/Alt-Left / Right | Move by word |
| Ctrl-K / Ctrl-U | Kill to line end / start |
| Ctrl-W, Alt-Backspace / Alt-D | Kill previous / next word |
| Ctrl-Y | Yank killed text |
| Up / Down | Move within multiline input, then through prompt history |
| Ctrl-R | Incremental backward history search; repeat for older matches |
| Ctrl-L | Repaint the screen |
| Ctrl-C | Clear input |
| Escape | Close a selector first; otherwise cancel the selected agent and its queued prompts |
| Ctrl-D on an empty prompt | Exit |
| Ctrl-Z (POSIX) | Suspend; `fg` restores the screen, terminal mode, and draft |

In history search, Backspace revises the query, Escape keeps the match for
editing, Ctrl-G restores the draft, and Enter submits the match.

## Conversation and inspection

Scrolling happens inside Claudia, not in terminal scrollback. The mouse wheel
moves three rows; PgUp/PgDn moves a page. New output preserves a scrolled-up
view; `↓ more below` indicates unseen output. Scroll to the end or submit a
prompt to follow new output again. Hold Shift while dragging for native text
selection (Ghostty's default); it selects the rows currently on screen.
On exit, the conversation in its current expanded/collapsed form is written
to terminal scrollback.

Tool and reasoning containers have clickable `▶` / `▼` toggles. Tools start
collapsed, showing the call and a short result preview; expanded tools show
arguments and the full result received by the model. Reasoning expands while
streaming. Clicked states survive redraws, resizes, and agent switching, but
are not persisted; `/clear` and `/resume` reset them.

- **Ctrl-T:** toggle all reasoning containers and persist the preference for
  collapsing finished reasoning.
- **Ctrl-O** or **`/details`:** inspect the selected agent's latest turn.
  Up/Down selects a step; Enter expands that step. Inside the inspector,
  Ctrl-T toggles all thinking and Ctrl-O toggles all tool sections.
- **`/settings`:** choose the [model's thinking level](providers.md#model-and-thinking-selection).

The bottom status row shows live activity, workspace, Git branch, named
session when present, model/thinking level, mode, and context usage. Activity
includes model waits, reasoning, output, tools, retries, compaction, and
stopping; `Ready` is green. Context usage updates after responses and
compaction. Transient provider failures are retried with exponential backoff;
Escape can cancel the retry wait.

## Sessions, workspace, and agents

| Command | Effect |
| --- | --- |
| `/resume` | Search saved Main sessions for the current folder; restore transcript and children, then append to the same session file |
| `/clear` | Start a fresh conversation with the current model/workspace |
| `/fork` | Name and switch to a branch of the conversation and task state; child ownership is not copied |
| `/compact` | Compact the selected idle conversation |
| `/cd <directory>` | Change the agent workspace; relative paths and `~` are supported |
| `/subagents` | Search and switch between Main and children; background work continues |

Sessions are recorded under `~/.claudia/sessions` unless `--no-session` is
used. Compaction preserves the full saved transcript, but resume sends only
the checkpoint and subsequent messages to the model. If a saved model is
unavailable, a configured model is required as fallback. Interrupted children
are restored as interrupted, not automatically restarted. With `--no-session`,
conversations and delegation remain in memory; `/resume` is disabled.

`/cd` rebuilds tools/instructions and reconnects MCP while preserving Main's
conversation and session file. The persisted session moves to the new
workspace. Workspace-bound children are released, not migrated; their saved
transcripts remain. It does not change the parent terminal shell's directory.

Each [subagent](tools/subagent.md) owns its conversation and processes, but
agents share workspace files: coordinate edits. Prompts queue independently
for the selected agent. Escape on Main cancels all children too; exiting
closes the whole group. Session, workspace, provider, settings, MCP, and mode
changes require Main selected and the group idle.

## Plan/Build and questions

`/plan` and `/build`, or Shift-Tab at the main prompt, change the mode without
starting work. After switching to Build, explicitly request implementation.
Main and children share the mode; switch only with Main selected and all
agents idle. Plan allows investigation and diagnostic tests/builds, but
instructs the agent not to edit sources, install, commit, or make external
changes. These are model instructions, not enforced tool permissions.
Mode is saved in sessions; a new process defaults to Build. To override:

```sh
java -jar target/claudia.jar --agent-mode plan
```

The [question tool](tools/question.md) displays one question from the requesting
agent. Choose a suggestion or type an answer and press Enter; Escape declines.
It preserves your chat draft and waits behind other open selectors. Queued
chat messages are not question answers. Print/JSON mode cannot collect answers;
RPC can answer them using `answer_question`.

For scripts that prompt through stdin, ask the agent to run the script and
relay its questions to you. It must send your reply to the same process via
[`shell_input`](tools/shell_input.md), not restart it. Processes use pipes,
not a PTY; scripts must flush prompts. Running processes are not restored
with sessions, and one-shot print mode closes them when the response ends.
See [`shell`](tools/shell.md) and [`run_process`](tools/run_process.md).

For MCP setup and interactive server/tool toggles, see
[README configuration](../README.md#mcp-servers) and
[code-lens](../code-lens/README.md). Terminal implementation lives in
[ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java) and
[the terminal package](../src/main/java/com/quaxt/claudia/terminal).
