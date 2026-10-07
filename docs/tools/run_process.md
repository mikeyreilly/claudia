# `run_process`

Runs an executable directly, without a shell, so each argument is passed exactly
as given.

Defined in [`LocalTools.runProcess()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
implemented by `ShellSessionManager.executeProcess`, with environment handling in
[`ProcessEnvironment`](../../src/main/java/com/quaxt/claudia/shell/ProcessEnvironment.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `executable` | string | required | Path to a native executable, or a name found on the child's `PATH`. Windows `.bat`/`.cmd` files are rejected. |
| `arguments` | string[] | required | Arguments. Each element is one argument, even if it contains spaces, quotes or newlines. |
| `working_directory` | string | workspace | Directory to run the child in. |
| `environment` | map | none | Environment variables to set for this child only. |
| `unset_environment` | string[] | none | Environment variable names to remove for this child. |
| `inherit_environment` | boolean | `true` | Start from Claudia's environment. If `false`, only the Windows startup variables are kept. |
| `stdout_log` | string | none | File that receives the complete stdout. |
| `stderr_log` | string | none | File that receives the complete stderr. Must be a different file from `stdout_log`. |
| `timeout` | number > 0 | none | Maximum process lifetime in seconds. |
| `yield_ms` | integer 0–30000 | `1000` | How long to wait before returning. Returning does not stop the process. |

## Behaviour

- stdout and stderr are captured **separately**, unlike [`shell`](shell.md).
  The result text has `stdout:` and `stderr:` sections. Each stream is limited
  to its most recent 2,000 lines or 50 KB. The log files, if given, receive the
  complete streams.
- The result details include `exit_code` (the child's real exit status), `pid`,
  `timed_out`, `elapsed_ms`, `cpu_time_ms`, `last_output_time`, both truncation
  flags and any log or stream errors.
- A non-zero exit, a timeout or a failure marks the result as an error.
- If the process is still running after `yield_ms`, it becomes a session.
  [`shell_input`](shell_input.md) can poll it, send it input or terminate it;
  use `process_tree` to see its descendants.

Use this tool for native builds and anything else that needs exact argument
boundaries or separate stdout and stderr.

## UI summary

`Running <executable>`
