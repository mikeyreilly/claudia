# `debug_pause`

Suspends a running target straight away and returns the new stop. It never
resumes the target itself. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `thread_id` | string | any thread | Thread to report as the stopped thread. |
| `wait_ms` | integer 0–30000 | `1000` | Kept for compatibility. It is validated but otherwise ignored, because pausing is synchronous. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

- If the target is already stopped, it returns `Already stopped` with the
  current stop.
- If the target is not running (for example, it has completed), it returns
  `invalid_state`.
- Otherwise it calls `VirtualMachine.suspend()` to suspend all threads and
  creates a stop with `reason: "pause"`. It returns the compact execution state,
  including the new `stop_id`.

Use this after an execution tool or [`debug_wait`](debug_wait.md) returns
`wait_expired: true`, if you want to look at the program while it is still
running.
