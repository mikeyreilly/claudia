# `debug_wait`

Waits for a debugger event without changing how the target is running. See
[debugger concepts](debugger.md#execution-and-waiting).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `wait_ms` | integer 0–30000 | `1000` | Maximum time to wait. |
| `cursor` | integer ≥ 0 | `0` | Only consider events after this event cursor. |
| `event_types` | enum[] | all | Event types to match: `launch`, `attach`, `detach`, `exit`, `stop`, `resume`, `breakpoint_resolved`, `breakpoint_pending`, `breakpoint_error`, `breakpoint_change` or `logpoint`. |
| `thread_id` | string | any | Only match events for this thread. |

## Behaviour

While the target is running, the tool waits until one of these happens:

- a stored event after `cursor` matches the filters,
- part of the event history has been discarded (a **gap**), or
- `wait_ms` runs out.

If the target is already stopped or completed, it returns at once and does not
pretend a new event arrived.

## Result

The compact execution state, plus:

| Field | Meaning |
| --- | --- |
| `event_available` | A stored event after `cursor` matches the filters. |
| `gap` | Events after `cursor` were discarded (the session keeps 512). Read [`debug_events`](debug_events.md) to see what remains. |
| `wait_expired` | The target is still running and nothing matched before the wait ran out. |
| `next_event_cursor` | Pass this as `cursor` next time. |

## Example

```json
{"session_id":"…","cursor":7,"event_types":["stop","exit"],"wait_ms":10000}
```
