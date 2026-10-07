# `shell_input`

Continues a process session started by [`shell`](shell.md) or
[`run_process`](run_process.md). It can poll for new output, write to stdin,
close stdin or terminate the process.

Defined in [`LocalTools.shellInput()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
implemented by `ShellSessionManager.interact`.

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | ID returned by `shell` or `run_process`. |
| `input` | string | none | Text to write to stdin, exactly as given; include `\n` to submit a line. At most 51,200 characters. The shell does not interpret it. |
| `close_stdin` | boolean | `false` | Close stdin after writing (sends EOF). |
| `terminate` | boolean | `false` | Kill the process and its descendants. Cannot be combined with `input` or `close_stdin`. |
| `process_tree` | boolean | `false` | Include the process and its descendants (pid, parent, command, CPU time) in the result details. |
| `yield_ms` | integer 0–30000 | `1000` | How long to wait for new output before returning. |

## Behaviour

- If you give neither `input`, `close_stdin` nor `terminate`, the call just
  polls.
- Input is written by a background thread, so a full pipe can't block the tool.
  Sending more input while the previous write is still pending is an error; poll
  first.
- If the process exits while the user is answering, the input is dropped and the
  call returns the final output instead.
- The result has the same form as `shell` (or `run_process` for direct
  processes). It contains only new output. When the process has finished, the
  result reports completion and the session is removed.
- An unknown `session_id` is an error. Sessions do not survive a conversation
  reset or a restart.

## Interactive scripts

This tool is what lets the agent drive scripts that ask questions:

1. `shell` starts the script and returns while it waits for input.
2. The agent shows you the prompt in chat and ends its turn.
3. Your next message is sent with `shell_input` as `input`, ending with `\n`.

The tool description tells the model never to make up answers on your behalf.

## UI summary

`Checking command <id>`, `Sending input to command <id>`,
`Closing command input <id>` or `Stopping command <id>`.
