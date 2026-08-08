# codingagent

This repository contains codingagent, a Java/JDK 25 coding-agent CLI. It is a
single Maven project and can be compiled ahead of time with GraalVM
native-image. It started out as a java port of pi.

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

Start the interactive codingagent TUI shell (backed by JLine) with:

```bash
java -jar target/codingagent.jar
```

Then run `/login`, open the displayed github.com device-login URL, and enter
the displayed code. On first login, the shell selects GitHub Copilot's GPT-5.4
when it is enabled for the account, otherwise it selects the first enabled
coding model. Credentials are stored in `~/.codingagent/auth.json`; `/logout`
removes them. Use `/models` to open the
searchable model selector; type to fuzzy-filter models and use Up/Down and
Enter to select. Enabled Copilot models are filtered to the signed-in account.
Model, thinking-level, and theme selections are saved in
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
currently configured model is used as a fallback.

The shell supports `/theme dark`, `/theme light`, and `/theme plain`; JLine
provides standard line editing, with Enter to submit, Ctrl-C to cancel input,
Ctrl-D to close the shell, and Ctrl-Z to suspend the foreground job on Unix.
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

`--api-key` overrides environment-based credentials. Without `--no-session`,
codingagent records an append-only transcript in `~/.codingagent/sessions`.

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
| Anthropic, OpenAI Responses, Google, OpenAI-compatible, GitHub Copilot providers | Implemented |
| Streaming agent loop and sequential tool calls | Implemented |
| `read`, `write`, `edit`, `bash`, `grep`, `find`, `ls` tools | Implemented |
| Headless `--print`, model listing, credentials, JSONL sessions | Implemented |
| Native image | Implemented |
| Interactive `/resume` session listing and restoration | Implemented |
| Manual and automatic context compaction | Implemented |
| JSON event mode and core JSONL RPC automation | Implemented |
| Interactive JLine prompt shell and streamed output | Implemented |
| Differential rendering, fuzzy selectors, mouse input, OSC 8 link primitives, themes, keybinding defaults | Implemented |

Extensions, Node-compatible data formats, non-core providers, and the Node
extension package manager are intentionally unsupported.
