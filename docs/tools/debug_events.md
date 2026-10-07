# `debug_events`

Returns the session's recorded debugger events, in order, starting after a
cursor. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `cursor` | integer ≥ 0 | `0` | Return events after this cursor. |
| `limit` | integer 1–100 | `20` | Maximum number of events. |
| `event_types` | enum[] | all | Event types to include (the same types as [`debug_wait`](debug_wait.md)). |
| `thread_id` | string | any | Only events for this thread. |

## Result

- `events`: each one has its own `cursor`, a `type` and type-specific `data`.
  Examples are a stop's location, a logpoint's value, a breakpoint's pending
  reason, or an exit code.
- `cursor`: the session's latest event cursor.
- `gap: true`: events after your `cursor` have been discarded. The session
  keeps the most recent 512.
- `truncated` and `next_cursor`: there are more matching events to read.

Events that don't match the filters are skipped, but the cursor still moves
past them.

This is how you read **logpoint** output: breakpoints with a `log_expression`
record `logpoint` events and don't stop the target.
