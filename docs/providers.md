# Providers and credentials

See [build and run](build.md) for installation, [terminal usage](terminal.md)
for interactive commands, and [the developer guide](developer.md) for runtime
internals. Use `java -jar target/claudia.jar --list-models` for current bundled
model IDs; examples below use placeholders, not recommendations for a model.

## Sign in

Start Claudia and run `/login`. The interactive login choices are:

| Choice | Provider ID | Authentication / billing |
| --- | --- | --- |
| GitHub Copilot | `github-copilot` | GitHub device authorization; models enabled for the signed-in account |
| OpenAI API key | `openai` | Platform API key; separately billed API usage |
| ChatGPT Plus/Pro | `chatgpt` | OpenAI Codex device authorization; subscription's included Codex usage |

Device login displays a URL and code to complete in your browser. Credentials
are saved in `~/.claudia/auth.json` (`%USERPROFILE%\.claudia\auth.json` on
Windows). Treat this file as a secret. `/logout` removes the stored credential
for the selected login provider; it does not unset environment variables.
Anthropic and Google use environment credentials rather than these login choices.

## Environment credentials

The CLI's core provider registry includes Anthropic, OpenAI Responses,
ChatGPT, Google, and GitHub Copilot.

| Provider ID | Environment variables |
| --- | --- |
| `anthropic` | `ANTHROPIC_API_KEY`; also `ANTHROPIC_AUTH_TOKEN` (Bearer authorization) or `ANTHROPIC_OAUTH_TOKEN` |
| `openai` | `OPENAI_API_KEY` (or the key saved through `/login`) |
| `google` | `GEMINI_API_KEY`, falling back to `GOOGLE_API_KEY` |
| `chatgpt`, `github-copilot` | Use `/login` for device credentials |

`--api-key` supplies an explicit key instead of normal environment-key lookup.
For Anthropic, a nonblank `ANTHROPIC_AUTH_TOKEN` takes precedence in the
adapter and is sent as a Bearer token; unset it when intending to use an API
key. Without that variable, environment-key lookup checks
`ANTHROPIC_OAUTH_TOKEN` before `ANTHROPIC_API_KEY`.

Select a model from the catalog, replacing `PROVIDER/MODEL_ID`:

```sh
java -jar target/claudia.jar --model PROVIDER/MODEL_ID \
  --print "Summarize the README" --no-session
```

A qualified `--model provider/model-id` selects both provider and model.
Alternatively use `--provider PROVIDER --model MODEL_ID`; a conflicting
provider in a qualified model is an error. In interactive mode `--provider`
without a model opens that provider's selector.

## Model and thinking selection

`/models` opens a searchable selector for the current provider (or the provider
just selected through `/login`). Type to fuzzy-filter, use Up/Down to navigate,
and Enter to select. Log in to another provider or start with `--provider` or
`--model` to change providers. Copilot choices are filtered to account access;
if refreshing access fails, only last-known enabled models are shown.

Interactive model and thinking selections are saved in
`~/.claudia/settings.json` and restored on the next interactive launch.
`--model` overrides the saved model for that run; selecting a model in the UI
updates the saved default. `/models` continues the current conversation,
including task state, children, and session file; `/clear` starts a new one.
Model/provider changes require Main selected and all agents idle.

Use `/settings` to select a thinking level. Available levels depend on the
model; the default is medium, clamped to the levels the model supports.
The selection becomes the default for later sessions. New
[subagents](tools/subagent.md) inherit Main's model and thinking level unless
explicitly overridden when created.

## Anthropic proxy / APIM

`ANTHROPIC_BASE_URL` must be an absolute HTTP(S) URL. Claudia trims trailing
slashes and sends Anthropic Messages requests to that endpoint. Configure
and authenticate your proxy separately; Claudia does not perform AAD/APIM
login on its behalf.

For an API-key proxy on a POSIX shell, with `ANTHROPIC_API_KEY` already set,
replace `MODEL_ID` with an Anthropic model from `--list-models`:

```sh
ANTHROPIC_BASE_URL=http://127.0.0.1:8787 \
  java -jar target/claudia.jar --model anthropic/MODEL_ID
```

For a proxy requiring Bearer authorization, set `ANTHROPIC_AUTH_TOKEN` instead.
When `ANTHROPIC_BASE_URL` is configured, the Copilot `/models` selector uses
cached entitlements rather than refreshing them. This avoids extra HTTP
traffic that can disrupt subsequent proxy streaming responses; it does not
make the entire Copilot catalog selectable.

Provider registration, authentication, and proxy handling live in
[ClaudiaOperations](../src/main/java/com/quaxt/claudia/ClaudiaOperations.java);
login and selectors live in
[ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java).
