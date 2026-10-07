# `debug_run_to`

Runs until a specific source line is reached, using a temporary breakpoint that
is removed after one hit. See
[debugger concepts](debugger.md#execution-and-waiting).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `source_path` | string | required | Source file of the target line. |
| `line` | integer ≥ 1 | required | 1-based target line. |
| `class_name`, `method_name`, `signature` | string | none | Narrow the match when the line resolves to more than one location. |
| `thread_id` | string | none | Only stop on this thread. |
| `wait_ms` | integer 0–30000 | `1000` | How long to wait for the next stop or exit. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

1. Finds the executable locations for `source_path:line` in **classes that are
   already loaded**. There must be exactly one. Otherwise it returns
   `ambiguous_location` and lists up to 10 candidates.
2. Installs a temporary breakpoint there, with ID `run-to-<uuid>`, that
   suspends all threads.
3. Resumes and waits, as [`debug_continue`](debug_continue.md) does. Another
   breakpoint can stop the target before it reaches the line.

## Limits

Lines in classes that haven't loaded yet, and targets given only as a method,
are not supported. For those, use
[`debug_breakpoints`](debug_breakpoints.md) with `one_shot: true`, then
`debug_continue`.
