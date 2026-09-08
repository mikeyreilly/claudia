# codingagent

This repository contains codingagent, a Java/JDK 25 coding-agent CLI. It is a
single Maven project and can be compiled ahead of time with GraalVM
native-image. It started out as a java port of pi.

`CodingAgentCli` is the entry point and owns command-line modes, terminal input,
and presentation. It delegates to `CodingAgentOperations`, which owns agent
execution, providers, tools, repository instructions, and persistence. The CLI
reads runtime snapshots and subscribes to `AgentEvent` updates; the runtime can
execute prompts and record sessions without a terminal.

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

Start the interactive codingagent TUI shell (backed by JLine) with:

```bash
java -jar target/codingagent.jar
```

Then run `/login` and choose GitHub Copilot, OpenAI API key, or ChatGPT
Plus/Pro. GitHub Copilot and ChatGPT Plus/Pro display a device-login URL and
code; the latter uses OpenAI's Codex authorization and your ChatGPT plan's
included Codex usage. The OpenAI API-key option is separate and uses Platform
API billing. GitHub Copilot selects GPT-5.4 when it is enabled for the account,
otherwise it selects the first enabled coding model. Credentials are stored in
`~/.codingagent/auth.json`; `/logout` removes the credential for the active model.
Use `/models` to open the searchable model selector; type to fuzzy-filter
models and use Up/Down and Enter to select. Enabled Copilot models are filtered
to the signed-in account.
Model and thinking-level selections are saved in
`~/.codingagent/settings.json` and restored when the next interactive session
starts. An explicit `--model` overrides the saved model for one run; choosing a
model through the interactive selector, including one opened by `--provider`,
updates the saved default. You can select another provider explicitly:

```bash
java -jar target/codingagent.jar \
  --model anthropic/claude-haiku-4-5
```

Use `/resume` to open a searchable list of saved sessions from the current
folder. Selecting one restores its model and visible conversation transcript,
and new messages continue appending to the same session file. The active
session is excluded from the selector. If a saved model is unavailable, the
currently configured model is used as a fallback. Use `/clear` to discard the
active conversation, clear the visible transcript, and start a fresh unnamed
session using the current model and workspace. When session persistence is
enabled, the new conversation is recorded in its own session file; with
`--no-session`, it is fresh in memory only. Use `/fork` to branch the current
conversation into a new session: the name prompt starts with the current
session name followed by ` fork` (or `fork` for an unnamed session), and the
shell switches to the new session after you submit the name.

Typing `/` opens a four-row, alphabetized command panel below the prompt; continue
typing to prefix-filter it, use Up/Down to navigate, and press Enter to insert
the selected command. JLine provides standard line editing: Enter submits,
Shift-Enter inserts a newline, Ctrl-C cancels input, Escape interrupts an active
agent turn, Ctrl-D closes the shell, and Ctrl-Z suspends the foreground job on
Unix.
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

## Repository instructions

When started inside a Git worktree, codingagent reads applicable `AGENTS.md`
files from the repository root through the current working directory. Their
contents are appended to the model's instruction context in that order, so
more deeply nested files are more specific. `AGENTS.override.md` takes
precedence over `AGENTS.md` when both are in the same directory. As local tools
move into a deeper descendant directory, any newly applicable instructions are
loaded before the next model request. Each file is reported once when it first
applies, for example `Found /Users/Michael.Reilly/xa/coding-agent/code-lens/AGENTS.md`.
These files are prompt text only; they do not impose separate filesystem
restrictions.

## RPC mode

`--mode rpc` accepts JSONL commands on standard input and emits JSONL responses
and agent events on standard output. It currently supports `prompt`, `abort`,
`get_state`, `get_available_models`, `set_model`, `get_messages`,
`get_last_assistant_text`, `new_session`, `compact`, and
`set_auto_compaction`.

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
| `read`, `write`, `edit`, `shell`, `grep`, `find`, `ls` tools | Implemented |
| OpenCode-compatible local/remote MCP servers, OAuth 2.1/PKCE, and interactive per-server/per-tool `/mcp` toggles | Implemented |
| Headless `--print`, model listing, credentials, JSONL sessions | Implemented |
| Native image | Implemented |
| Interactive `/resume` session listing and restoration | Implemented |
| Manual and automatic context compaction | Implemented |
| JSON event mode and core JSONL RPC automation | Implemented |
| Interactive JLine prompt shell and streamed output | Implemented |
| Differential rendering, fuzzy selectors, mouse input, OSC 8 link primitives, fixed terminal styling, keybinding defaults | Implemented |

Extensions, Node-compatible data formats, non-core providers, and the Node
extension package manager are intentionally unsupported.
