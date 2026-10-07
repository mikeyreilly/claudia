# `debug_status`

Shows a debug session's full state: what it is doing now, its current stop (if
any), and how it was launched. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Description |
| --- | --- | --- |
| `session_id` | string | The session to inspect. |

## Result

Everything in the compact execution state:

- `status` (`running`, `stopped` or `completed`)
- `stop`, which includes `stop_id`, `reason`, `thread_id` and `location`
- `output_cursor`, `output_complete` and `event_cursor`
- `exit_code`, `failure` and `completion_reason`, when they apply

It adds:

- `target`, and `ownership` (`launched` or `attached`)
- `breakpoint_count`, plus the first 10 breakpoints with their verification
  state. Use [`debug_breakpoints`](debug_breakpoints.md) to see all of them.
- `arguments` and `jvm_options`. Values that look like secrets are
  `[REDACTED]`.
- `environment_overrides`: the names only, never the values.
- `runner_pid`, for test launches.
- `capabilities`, for example JDI and virtual-thread support, and whether the
  target is still connected.

Call this first when you need a fresh `stop_id`.
