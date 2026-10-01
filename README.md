# codingagent

This repository contains codingagent, a Java/JDK 25 coding-agent CLI. It is a
single Maven project and can be compiled ahead of time with GraalVM
native-image. It started out as a java port of pi.

`CodingAgentCli` is the entry point and owns command-line modes, terminal input,
and presentation. It delegates to `CodingAgentOperations`, which owns agent
execution, providers, tools, repository instructions, and persistence. The CLI
reads runtime snapshots and subscribes to `AgentEvent` updates; the runtime can
execute prompts and record sessions without a terminal.
`ShellSessionManager` owns subprocesses and their I/O independently of the
agent loop, with explicit cleanup and focused process tests.
Each `CodingAgentOperations` instance owns its conversation, provider
configuration, shell sessions, and MCP connections. Embedders should use it in
try-with-resources; `close()` cancels work and releases those resources. The CLI
creates and closes its own runtime. Separate runtimes can use different
`CodingAgentPaths` without sharing credentials or conversation state in memory.
Built-in tools are registered in `LocalTools`: each definition keeps its
metadata, typed parameters, handler, and call description together. Parameter
declarations generate the model-facing schema and validate arguments before
execution. Adding a definition to the registry requires no new tool-kind enum
or execution/presentation switch branches. `ToolRegistryTest` exercises these
contracts, including a custom tool bound to independent contexts.

The `task_state` tool keeps each agent's coarse tasks, findings, and constraints
outside the conversation. Use `action=add_task` with a description and omit the
ID; new tasks start as `todo` and receive IDs such as `#1`. Blank optional IDs
are treated as omitted. `update_task` accepts an ID and
optional description, status (`todo`, `in_progress`, `done`, or `cancelled`), note,
and `depends_on` task IDs. An empty note or dependency list clears that field.
`remove_task` deletes a task and its dependency references. `add_finding` and
`add_constraint` accept `text` and assign IDs such as `F1` and `C1`; use
`remove_finding` or `remove_constraint` with the ID to delete one. `list` shows
all IDs and details. IDs are never reused within a session. Valid changes are
saved to the session journal; compaction and resume retain them. `/fork` copies
the current state into an independent session, including with `--no-session`.
Each delegated child has its own state.

## Build and run

Set `JAVA_HOME` to a GraalVM JDK 25 installation, then run:

```bash
mvn -B test
mvn -B package
java -jar target/codingagent.jar --list-models
`````

`codingagent.jar` is the runnable uber JAR; `codingagent-thin.jar` contains only the project's own
classes. Packaging publishes the uber JAR by renaming a completed temporary
archive, so rebuilding while a prior copy is running does not corrupt that
JVM's classpath.

Build a native executable with:

```bash
mvn -B -Pnative package
./target/codingagent --list-models
```

Run a coding prompt with a core provider:

```bash
java -jar target/codingagent.jar \
  --model anthropic/claude-haiku-4-5 \
  -p "Summarize the README" \
  --no-session
```

For a Clojure tools.deps monorepo, use `--aliases :dev:reporting` to force that
alias basis on every code-lens MCP query, context lookup, and SQL read during
this codingagent run. The option is per-run and is not saved to settings:

```bash
java -jar target/codingagent.jar --aliases :dev:reporting
```

### Anthropic APIM proxy

`codingagent` honors the standard `ANTHROPIC_BASE_URL` and
`ANTHROPIC_API_KEY` environment variables. After configuring
`cai-claude-apim-proxy`, run codingagent in that configured environment:

```bash
ANTHROPIC_BASE_URL=http://127.0.0.1:8787 \
ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
java -jar target/codingagent.jar --model anthropic/claude-opus-4-6
```

The proxy handles its own AAD/APIM authentication; codingagent only sends the
Anthropic Messages request to the proxy. When `ANTHROPIC_BASE_URL` is set,
`/models` does not refresh GitHub Copilot entitlements, avoiding a JDK HTTP
client interaction that can break subsequent proxy streaming responses.

Start the interactive codingagent TUI shell with:

```bash
java -jar target/codingagent.jar
```

The interactive shell supports [Ghostty](https://ghostty.org) on macOS and
Linux (x86_64 and aarch64), and Windows consoles with virtual terminal input
and output (for example Windows Terminal). Its terminal layer
(`com.quaxt.codingagent.terminal`) controls POSIX ttys through termios and
Windows consoles through the Win32 console API. Other xterm-compatible
terminals may work but are not tested. Windows has no process-group suspend
shortcut. When standard input or output is redirected, interactive mode exits
with an error. `--print`, `--mode json`, and `--mode rpc` do not use the
terminal and work anywhere Java runs. The runnable JAR's manifest enables
native access for the terminal layer; when running from a classpath instead,
pass `--enable-native-access=ALL-UNNAMED` to avoid the JDK's warning. Native
executables support interactive mode on Linux and on Apple silicon Macs, where
GraalVM supports foreign-function calls.

Then run `/login` and choose GitHub Copilot, OpenAI API key, or ChatGPT
Plus/Pro. GitHub Copilot and ChatGPT Plus/Pro display a device-login URL and
code; the latter uses OpenAI's Codex authorization and your ChatGPT plan's
included Codex usage. The OpenAI API-key option is separate and uses Platform
API billing. GitHub Copilot selects GPT-5.4 when it is enabled for the account,
otherwise it selects the first enabled coding model. Credentials are stored in
`~/.codingagent/auth.json`; `/logout` removes the credential for the active model.
Use `/models` to open the searchable model selector for the current provider
(or the provider just chosen through `/login`); type to fuzzy-filter models and
use Up/Down and Enter to select. Log in to another provider or start with
`--provider` or `--model` to switch providers. Enabled Copilot models are filtered
to the signed-in account; if refreshing access fails, only the last-known enabled
models are shown.
GPT-6.1 Sol is available as `openai/gpt-6.1-sol` with an OpenAI API key or
`chatgpt/gpt-6.1-sol` with ChatGPT login. It supports thinking levels `low`,
`medium`, `high`, `xhigh`, and `max`.
Model and thinking-level selections are saved in
`~/.codingagent/settings.json` and restored when the next interactive session
starts. An explicit `--model` overrides the saved model for one run; choosing a
model through the interactive selector, including one opened by `--provider`,
updates the saved default. Changing models with `/models` continues the current
session, preserving conversation history, task state, subagents, and the session
file; resumed sessions use the latest selected model. Use `/clear` to start a
fresh conversation. You can select another provider explicitly:

```bash
java -jar target/codingagent.jar \
  --model anthropic/claude-haiku-4-5
```

Use the `subagent` tool to delegate a task to a separate conversation. Supply
`task` and optionally `name`; when creating a child, `model` selects a model by
ID from the current provider or by `provider/model`, and `thinking_level` selects
`off`, `minimal`, `low`, `medium`, `high`, `xhigh`, or `max`. Omitted selections
inherit Main's model and thinking level. Supply `agent_id` to give an existing
child another task with its original model and thinking level. Main waits for that
request's final answer and receives the child's ID, name, status, model, and
thinking level. Reasoning, searches, intermediate output, and tool results stay
in the child's transcript. Children inherit the workspace, instructions, and
effective local/MCP configuration, and cannot delegate further.
All agents share files in the workspace, so coordinate edits to the same files.
Each agent owns its shell processes, MCP connections, and conversation.

Use `/subagents` to search and switch between Main and children in creation
order. The selected conversation supplies the transcript, streamed output,
activity, model/context information, and `/details` (Ctrl-O) inspector. You can
keep typing while work runs; submitted prompts queue in order for the selected
agent. Direct chats with a child stay in that conversation and do not change the
answer returned to an earlier delegated request. Background agents keep running
when you switch views.

Escape closes a selector first. Otherwise it cancels the selected agent and
clears its queued prompts; cancelling Main also cancels all its children.
Cancelling a delegated child returns a tool error with its ID to Main. `/compact`
targets the selected idle conversation. Session, workspace, provider, settings,
and MCP changes require Main to be selected and the entire group to be idle.
Exiting closes the whole group.

Each child is saved in its own JSONL file linked to its parent before work starts.
Accepted conversation steps are saved incrementally, and compaction preserves the
full transcript. `/resume` lists Main sessions and restores their children;
previously running children show as interrupted and do not restart automatically.
Completed children remain available for chat or further delegation. `--no-session`
provides the same delegation and navigation in memory. Clearing or leaving a
session releases its child runtimes; `/fork` creates a new Main without copying
child ownership. Delegation is also available in print and RPC modes; RPC events
continue to describe Main, except for question events identifying the requesting
agent. There are no RPC navigation commands.

Use `/resume` to open a searchable list of saved sessions from the current
folder. Selecting one restores its model and visible conversation transcript,
and new messages continue appending to the same session file. The active
session is excluded from the selector. If a saved model is unavailable, the
currently configured model is used as a fallback. Use `/clear` to discard the
active conversation, clear the visible transcript, and start a fresh unnamed
session using the current model and workspace. When session persistence is
enabled, the new conversation is recorded in its own session file; with
`--no-session`, it is fresh in memory only. Use `/cd <directory>` to change
the agent workspace without restarting the shell. Relative paths resolve from
the current agent workspace and `~` is supported. A workspace change rebuilds
local tools and instruction context and reconnects MCP servers while preserving
the Main conversation and its session file. Persisted sessions move to the new
workspace, so `/resume` lists them there after a restart. Workspace-bound child
runtimes are released rather than migrated; their saved transcripts remain
available in their original session files. `/cd` does not change the parent
terminal shell's directory. Use `/fork` to branch the current conversation into
a new session: the name prompt starts with the current session name followed by
` fork` (or `fork` for an unnamed session), and the shell switches to the new
session after you submit the name.

Typing `/` opens a four-row, alphabetized command panel below the prompt; continue
typing to prefix-filter it, use Up/Down to navigate, and press Enter to insert
the selected command. The prompt editor uses Emacs-style keys: Enter submits;
Shift-Enter, Ctrl-Enter, Alt-Enter, or Ctrl-J inserts a newline; Ctrl-A/Ctrl-E
(or Home/End) move to the start/end of the line; Alt-B/Alt-F (or
Ctrl/Alt-Left/Right) move by word; Ctrl-K/Ctrl-U kill to the end/start of the
line, Ctrl-W and Alt-Backspace kill the previous word, Alt-D kills the next
word, and Ctrl-Y yanks; Up/Down move between lines of a multiline prompt and
then through this session's prompt history; Ctrl-R searches prompt history as
you type (repeat Ctrl-R for older matches, Backspace to revise the query,
Escape to edit the match, Ctrl-G to restore the draft, Enter to submit it);
Ctrl-L repaints the screen. Pasted text keeps its line breaks. Ctrl-C clears
the input, Escape interrupts an active agent turn, Ctrl-D on an empty prompt
closes the shell, and Ctrl-Z
suspends the foreground job.
A status bar on the bottom terminal row starts with the shell's live activity,
followed by the working directory (with the home directory abbreviated to `~`),
the checked-out Git branch, the current named session when present, and the
model, thinking level, and context-window use. For example:
`● Ready │ ~/xa/coding-agent [main]  GPT-5.6 Sol Max (0%)`.
`Ready` is the only green activity, making it clear when the current turn has settled and the shell can accept another prompt. Slash commands
are identified while they run. During an agent turn, the status distinguishes
preparing tools, waiting for the model, reasoning, responding, preparing or
running a tool, retrying, compacting, and stopping;
active phases include an elapsed timer or retry countdown. Activity is retained
before workspace/model metadata when the terminal is narrow. The context
percentage updates after each assistant response and after `/compact`.
Transient provider and connection failures (including HTTP 503 responses) are
automatically retried up to three times with exponential backoff; Escape also
cancels a pending retry.
After `fg`, the conversation screen, terminal mode, prompt, and partially
entered input are restored; shell output produced while codingagent was
suspended is replaced by the redrawn codingagent screen.

Reasoning-capable models default to medium thinking, and reasoning summaries
stream in a muted block before each answer or tool call. Ctrl-T hides or shows
those blocks and persists the choice. Ctrl-O (or `/details`) opens the latest
turn's reasoning/tool-step inspector: use Up/Down to select a step and Enter to
expand only that step; Ctrl-T and Ctrl-O inside the inspector toggle all
thinking and tool sections, respectively.

Use `/settings` to select the thinking level. The available levels are
model-specific; for example, GitHub Copilot's GPT-5.6 Terra offers `max`. The
selection becomes the default for future sessions and is clamped when the
selected model supports fewer levels.

## Plan mode and questions

Use `/plan` to investigate a change and develop an implementation plan before
editing code. Use `/build` to return to implementation, or press Shift-Tab at the main
chat prompt to toggle modes. The status bar shows the current mode. Switching
preserves the conversation and starts no work: after switching to Build, send an
explicit implementation request.

Main and all its subagents share one mode. Switch with Main selected and the
entire group idle, including queued prompts and outstanding questions. Plan uses
the same model, thinking level, and tools as Build. Its instructions advise the
model to explore first, clarify material ambiguity, and present an actionable
Markdown plan in chat. Diagnostic tests/builds may produce disposable output or
caches. Source edits, automatic fixes, installs, commits, and external changes
are outside the planning workflow. These are model instructions, not enforced
tool permissions.

The local `question` tool asks one question at a time, with optional suggested
answers. The terminal shows the requesting agent, lets you select a suggestion
or type a custom answer, and waits for Enter to submit. Escape declines without
supplying an answer. Questions wait while another selector or auxiliary prompt
is open and preserve your chat draft and cursor. The requesting agent waits;
other agents can continue. Queued chat messages are not used as question answers.
The tool is available in Build as well as Plan.

Mode is saved with each session. Resume restores it; clear, fork, workspace
changes, and model changes retain the current selection. A new process defaults
to Build, independently of earlier sessions. Override that with
`--agent-mode plan` in interactive, print, JSON, or RPC usage:

```bash
java -jar target/codingagent.jar --agent-mode plan
java -jar target/codingagent.jar --agent-mode plan \
  --model anthropic/claude-haiku-4-5 --print "Plan the cache refactor"
```

With `--no-session`, mode and question answers stay in memory. Completed questions
and answers otherwise appear in the normal saved tool transcript. Interrupted
questions are not automatically reopened on resume. Print/JSON mode cannot
collect answers: the tool returns `unavailable` and instructs the model to present
the unresolved question in its response, without waiting for stdin.

## Java debugger tools

In the JVM CLI (`java -jar target/codingagent.jar`), ask the agent to use
`debug_launch` with exactly one of `main_class` (a Java class, for example
`com.example.Main`) or `test_selector` (for example
`com.example.CalculatorTest#adds`). For a main class, compiled classes in
`target/classes` are used by default when present; supply `classpath` and
`arguments` as needed. Set `stop_on_entry: true` to inspect the initial stop
before execution proceeds. Otherwise, set a breakpoint with
`debug_breakpoints` (`action: add`, with a source path and line or class and
method) and use the returned `session_id` to track the target. Breakpoints can
be pending until their class loads; check their reported resolution. Once a
matching class loads, pending reasons distinguish unavailable debug information,
source/method mismatches, and lines without executable code; breakpoints never
silently move to a nearby line.

At a stop, get the current `stop_id` from `debug_status` or `debug_wait`.
Pass it with `session_id` to `debug_continue`; then call `debug_wait` to wait
up to its finite `wait_ms` for an event. Inspect a stopped target with
`debug_threads`, `debug_stack`, `debug_variables`, `debug_object`,
`debug_source`, or `debug_exception`. Use `debug_events` and `debug_output`
with nonnegative integer cursors for event and captured stdout/stderr pages;
use the returned `next_cursor` for the next page. `debug_source` uses the stopped
frame's line when `line` is omitted or `0`; a positive value requests a specific
1-based line. A `stop_id` becomes
stale after resuming; get the new one before inspecting the next stop. Finish
with `debug_detach` (`leave_running: true` to leave the JVM running, or
`terminate: true, leave_running: false` for a launched target only). Set `close_session: true`
after reading the final status/output to release its retained record. Detaching
an attached JVM never terminates it.

Limits: these tools debug Java JVM processes from the JVM CLI only; the
GraalVM native executable does not support JDI debugging. `test_selector`
uses a single-project Maven Surefire/JUnit Jupiter setup and an already
compiled top-level test class in `target/test-classes`: use a fully qualified
class or `class#method` for an unambiguous zero-argument `@Test`, not a JUnit
unique ID. Only the Surefire goal runs (not the entire Maven test lifecycle).
It does not support arbitrary test engines, nested tests, or
reactor-child selection. To attach instead of launching, use `debug_attach`
with an explicit JDWP-enabled JVM `pid` and `consent: true`; target-selector
lookup is not supported. JDI cannot retrieve stdout/stderr from an attached JVM;
use its existing logs. `debug_evaluate` supports bounded read-only inspection:
local/`this` field paths, array subscripts (including local indices and nested
arrays), array `.length`, literals (including escaped double-quoted strings),
and simple comparisons. Examples: `numbers[index]`, `items[0].provider`,
`matrix[0].length`, and `model.provider == "openai"`. Strings compare by content;
other object references compare by identity. String literals stay in the
debugger rather than allocating objects in the target. The same grammar applies
to breakpoint conditions and log expressions. Expressions are limited to 256
characters and 16 nested subscripts; calls, mutation, arithmetic, and arbitrary
Java evaluation remain unsupported, even if `allow_side_effects` is supplied. Events, output, source windows, and
inspection results are bounded and may require paging; they are not an
unlimited transcript. Custom attach source roots/path mappings and arbitrary
collection enumeration are not supported yet. Captured output redacts known sensitive environment
values, and sensitive variable/field names are hidden by default. Arbitrary
target memory and expressions can still contain secrets: do not request a value
you would not want in the agent transcript.

## Interactive scripts

You can ask codingagent to run a script and supply information when it asks:
for example, "Run the import script; when it asks for a link, ask me for it."
The agent reads the script's prompt, asks you in chat, and sends your next reply
to the same running process. Scripts can ask multiple questions this way.

The `shell` tool returns after at most `yield_ms` (default 1000 milliseconds,
maximum 30000), with a session ID if the command is still running. The agent
uses `shell_input` with that ID to read subsequent output or write literal
`input` to stdin, including a newline to submit a line. `close_stdin` sends EOF;
`terminate` stops the command and its children. An optional `timeout` limits
the process's total lifetime, including time spent waiting for your reply.
Each call returns only new output, retaining the most recent 2,000 lines or
50KB when output is truncated so that a prompt after a long log stays visible.

For native build commands, `run_process` runs an executable directly with an
`arguments` array. Each element is passed as one argument, including embedded
spaces, quotes, and newlines. It returns separate `stdout` and `stderr`, the
child's `exit_code`, `timed_out`, elapsed time, CPU time when available, and
the last output time. `stdout_log` and `stderr_log` save complete raw streams
to separate files. `environment`, `unset_environment`, and
`inherit_environment` change only the child environment. A running process
uses the same `shell_input` session API; set `process_tree` when polling to
inspect descendants.

On Windows, `preflight_posix` checks a requested `msys2` or `git-bash` layer
before a build. It reports the resolved tools, Windows and POSIX paths,
compiler, TMPDIR write access, missing tool package hints, and Visual Studio
toolsets found through `vswhere`. It accepts the same child environment
settings as `run_process` and does not install packages.

Shell processes survive ordinary chat turns. Cancelling an active turn,
resetting the conversation, or exiting codingagent stops them. Running processes
are not saved with conversations and cannot be restored after a restart.
One-shot `--print` mode closes processes when the response ends; use the
interactive CLI or a persistent RPC connection for exchanges across turns.

Commands use stdin/stdout pipes, so prompts must be flushed by the script.
Python is launched with `PYTHONUNBUFFERED=1` unless you already set that variable.
PowerShell's `Read-Host` is adapted to read and write through these pipes.
Programs requiring a PTY, full-screen terminal interaction, or direct console
keyboard access are not supported.

## MCP servers

codingagent reads MCP server definitions from the `mcp` object in
`~/.codingagent/settings.json`. Local and remote definitions use the same
shape as OpenCode, and `{env:NAME}` and `{file:path}` substitutions are
supported:

```json
{
  "mcp": {
    "local-tools": {
      "type": "local",
      "command": ["npx", "-y", "@modelcontextprotocol/server-everything"],
      "environment": { "TOKEN": "{env:LOCAL_TOKEN}" },
      "enabled": true
    },
    "remote-tools": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer {env:MCP_TOKEN}" },
      "enabled": true,
      "timeout": 30000
    }
  }
}
```

Relative local-server `cwd` values resolve from the workspace. MCP tools are
exposed as `<server>_<tool>`, matching OpenCode's name sanitization. In the
interactive shell, `/mcp` opens the configured-server list; Enter connects,
disconnects, authenticates, or retries the selected server. Press Tab on a
connected server to open its tool list, then press
Enter to individually enable or disable a selected tool. Server and tool
toggles are saved to `~/.codingagent/settings.json` and restored by future
codingagent processes; a disabled tool also stays disabled if its server
reconnects during the current process. Tool overrides are stored as an
optional `disabledTools` array of raw MCP tool names on the server definition.
Streamable HTTP and legacy HTTP+SSE servers are supported.

Noisy keys can be removed recursively from JSON results for selected tools with
an optional per-server `resultFilters` list. Tool patterns support `*` and `?`;
plain-text results pass through unchanged. For example:

```json
{
  "mcp": {
    "atlassian-mcp": {
      "type": "remote",
      "url": "https://example.atlassian.net/mcp",
      "resultFilters": [
        { "tool": "*", "dropKeys": ["avatarUrls", "self"] }
      ]
    }
  }
}
```

Remote OAuth is discovered automatically from the MCP server's
`WWW-Authenticate` challenge and well-known metadata. When `/mcp` reports
`Authentication required`, select the server and press Enter. codingagent opens
the authorization page, receives the loopback callback, uses PKCE S256, and
dynamically registers a client when the authorization server supports it.
Tokens and registered-client details are stored with user-only permissions in
`~/.codingagent/mcp-auth.json`; access tokens are refreshed automatically.
codingagent can also import a matching OpenCode credential (same configured
server name and exact URL).

OAuth can be customized for servers that require a pre-registered client:

```json
{
  "mcp": {
    "remote-tools": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "oauth": {
        "clientId": "my-client-id",
        "clientSecret": "{env:MCP_CLIENT_SECRET}",
        "scope": "tools:read tools:write",
        "callbackPort": 19876
      }
    }
  }
}
```

`redirectUri` may be used instead of `callbackPort`, but must be an HTTP
loopback URL that codingagent can listen on. Explicit `Authorization` headers
take precedence over OAuth discovery. Set `"oauth": false` to disable OAuth
for a remote server.

`--api-key` overrides environment-based credentials. Without `--no-session`,
codingagent records an append-only transcript in `~/.codingagent/sessions`.
Successful manual and automatic compactions are recorded as resume boundaries:
the original transcript remains inspectable, while a resumed session sends only
the saved checkpoint and messages added after that compaction.

## Agent instructions

For personal defaults, create `~/.codingagent/AGENTS.md`. codingagent reads
this optional user-owned instruction file into every new agent session. Its
contents follow the working-directory context and precede any per-run
`--system-prompt` text and repository instructions. This follows the
cross-agent `AGENTS.md` convention while keeping the file with codingagent's
other settings.

When started inside a Git worktree, codingagent also reads applicable
`AGENTS.md` files from the repository root through the current working
directory. Their contents are appended to the model's instruction context in
that order, so more deeply nested files are more specific than the personal
default. `AGENTS.override.md` takes precedence over `AGENTS.md` when both are
in the same repository directory. As local tools move into a deeper descendant
directory, any newly applicable instructions are loaded before the next model
request. Each instruction file is reported once when it first applies, for
example `Found /Users/Michael.Reilly/xa/coding-agent/code-lens/AGENTS.md`.
Missing or unreadable instruction files are ignored. These files are prompt
text only; they do not impose separate filesystem restrictions.

## RPC mode

`--mode rpc` accepts JSONL commands on standard input and emits JSONL responses
and agent events on standard output. It currently supports `prompt`, `abort`,
`get_state`, `get_available_models`, `set_model`, `get_messages`,
`get_last_assistant_text`, `new_session`, `compact`, `set_auto_compaction`,
`set_agent_mode`, and `answer_question`.

Prompts queue in order, and their response IDs are completed when the work
finishes. Prompt and compaction execution leave the input loop responsive to
state requests, question replies, and aborts. Mode, model, session, and settings
changes require the whole group to be idle; wait for outstanding prompts to
complete before issuing them.

`get_state` includes `agentMode` (`build` or `plan`) and `pendingQuestions`.
Each pending question and `question_requested` event includes `questionId`,
`agentId`, `question`, and `options` (labels and descriptions). Reply with answer
text, including the selected label when choosing a suggestion, or explicitly
decline:

```json
{"id":"mode-1","type":"set_agent_mode","agentMode":"plan"}
{"id":"reply-1","type":"answer_question","questionId":"<request-id>","answer":"My preferred approach"}
{"id":"reply-2","type":"answer_question","questionId":"<request-id>","decline":true}
```

`question_resolved` identifies the same request and includes its `status`
(`answered`, `declined`, or `unavailable`) and answer text when supplied.
Duplicate or stale replies fail without changing the conversation. Question
events include delegated agents even though ordinary RPC conversation events
remain scoped to Main. EOF makes pending and future questions unavailable and
drains already accepted prompts before exiting, so piped commands do not hang
waiting for an answer.

```bash
printf '%s\n' '{"id":"state-1","type":"get_state"}' |
  java -jar target/codingagent.jar \
  --mode rpc --model anthropic/claude-haiku-4-5 --no-session
```

## Current feature coverage

| Area | Status |
| --- | --- |
| Anthropic, OpenAI Responses, ChatGPT Plus/Pro, Google, OpenAI-compatible, GitHub Copilot providers | Implemented |
| Streaming agent loop and sequential tool calls | Implemented |
| `read`, `write`, `edit`, `shell`, `run_process`, `preflight_posix`, `grep`, `find`, `ls` tools | Implemented |
| OpenCode-compatible local/remote MCP servers, OAuth 2.1/PKCE, and interactive per-server/per-tool `/mcp` toggles | Implemented |
| Headless `--print`, model listing, credentials, JSONL sessions | Implemented |
| Native image | Implemented |
| Interactive `/resume` session listing and restoration | Implemented |
| Plan/Build modes and local clarification questions in the terminal and RPC | Implemented |
| Manual and automatic context compaction | Implemented |
| JSON event mode and core JSONL RPC automation | Implemented |
| Interactive prompt shell and streamed output (Ghostty on macOS and Linux) | Implemented |
| Differential rendering, fuzzy selectors, mouse input, OSC 8 link primitives, fixed terminal styling, keybinding defaults | Implemented |

Extensions, Node-compatible data formats, non-core providers, and the Node
extension package manager are intentionally unsupported.
