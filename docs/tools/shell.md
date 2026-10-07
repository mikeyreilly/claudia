# `shell`

Runs a shell command in the agent workspace. If the command is still running
when the tool returns, it stays alive as a *session* that
[`shell_input`](shell_input.md) can poll or send input to.

Defined in [`LocalTools.shell()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
processes are managed by
[`ShellSessionManager`](../../src/main/java/com/quaxt/claudia/shell/ShellSessionManager.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `command` | string | required | Command text passed to the shell. |
| `timeout` | number > 0 | none | Maximum total lifetime of the process in seconds, including time spent waiting for the user. If omitted, there is no deadline. |
| `yield_ms` | integer 0–30000 | `1000` | How long to wait for the command before returning. Returning does **not** stop the process. |

## Shell

- On macOS/Linux, the command runs as `/bin/bash -c <command>`.
- On Windows, it runs as `powershell.exe -NoProfile -NonInteractive -Command`.
  A preamble first sets UTF-8 encodings and replaces `Read-Host` so that it
  reads from and writes to the pipes. The tool description gives the detected
  PowerShell version.

## Behaviour

- stdout and stderr are merged into one stream. Communication uses pipes, not a
  PTY, so programs must flush their prompts. Full-screen or console-only
  programs are not supported.
- `PYTHONUNBUFFERED=1` is set unless the environment already defines it.
- The tool waits up to `yield_ms` and then returns the output so far:
  - **Finished:** the output ends with `[Command exited with code N.]`, and the
    session is removed. A non-zero exit code marks the result as an error.
  - **Still running:** the output ends with a notice giving the session ID and
    telling the model to use `shell_input`. If the script is waiting for user
    input, the notice tells the model to ask the user and end its turn.
- Each poll returns only output produced since the previous poll. If there was
  more than 2,000 lines or 50 KB, the **most recent** output is kept, so a
  prompt after a long log stays visible.
- The result details include `session_id`, `status`
  (`running`/`stopping`/`exited`), `exit_code`, `timed_out`, `elapsed_ms`,
  `cpu_time_ms`, `last_output_time` and `child_count`.

## Lifetime

- A session survives across chat turns.
- Cancelling the active turn, resetting the conversation or exiting Claudia
  kills it, together with its child processes.
- Sessions are not saved with the conversation, and `--print` mode closes them
  when the response ends.
- At most 32 sessions can be open per agent.

## UI summary

The command, on one line.
