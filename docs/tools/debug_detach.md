# `debug_detach`

Ends a debug session. It either leaves the target running or terminates it, and
can also release the session's stored record. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session to end. |
| `leave_running` | boolean | `true` | Detach and let the target keep running. |
| `terminate` | boolean | `false` | Kill the target and its descendants. Launched targets only; requires `leave_running: false`. |
| `close_session` | boolean | `false` | Also delete the session's stored status, events and output. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

- The disposition must be explicit: either `leave_running: true`, or
  `terminate: true` with `leave_running: false`. Anything else is
  `invalid_argument`.
- `terminate` on an attached session returns `permission_required`.
- Detaching disposes of the JDI connection and records a `detach` event. The
  session becomes `completed`, and its stop and object references are cleared.
- When terminating, child processes are killed first, then the target. If the
  target is still alive afterwards, the result is `termination_failed`.
- Without `close_session`, the session record stays, so you can still read its
  final status, events and output. Closing it frees one of the limited session
  slots.

## Result

`status: "completed"` with a summary, `session_id`, `pid` and `terminated`.
