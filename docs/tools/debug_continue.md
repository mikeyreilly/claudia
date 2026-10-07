# `debug_continue`

Resumes a stopped target and waits for its next stop or exit. See
[debugger concepts](debugger.md#execution-and-waiting).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session to resume. |
| `stop_id` | string | required | The current stop. An old one is rejected with `stale_stop`. |
| `thread_id` | string | none | Accepted, but ignored: `debug_continue` always resumes whatever the current stop suspended. |
| `wait_ms` | integer 0–30000 | `1000` | How long to wait for the next stop or exit. `0` returns straight away. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

1. Checks that `stop_id` is the current stop.
2. Clears the stop, its object references and any pending exception, watchpoint
   or method-exit context. Records a `resume` event.
3. Resumes the threads that were suspended. That is all threads, or just the
   event thread if the breakpoint used `suspension_policy: event_thread`.
4. Waits up to `wait_ms`, then returns the compact execution state:
   - `stopped`, with a new `stop` (including its `stop_id`), or
   - `completed`, with `exit_code` and `completion_reason`, or
   - `running`, with `wait_expired: true`. The target **keeps running**; call
     [`debug_wait`](debug_wait.md) or [`debug_pause`](debug_pause.md).

If the tool call is cancelled while waiting, the target stays resumed.
