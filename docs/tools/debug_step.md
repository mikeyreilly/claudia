# `debug_step`

Steps one source line on a suspended thread: into, over or out. Then it waits
for the step to finish. See
[debugger concepts](debugger.md#execution-and-waiting).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `direction` | enum | `over` | `into`, `over` or `out`. |
| `thread_id` | string | the stopped thread | Suspended thread to step. |
| `skip_filters` | string[] | none | Class patterns to step through without stopping in them, for example `java.*`. |
| `wait_ms` | integer 0–30000 | `1000` | How long to wait for the step to finish. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

- Replaces any earlier step request with a JDI `StepRequest`
  (`STEP_LINE`, with the chosen depth, count filter 1). It suspends all threads
  when it completes.
- `skip_filters` become class exclusion filters on that request.
- The target is then resumed and waited on in the same way as
  [`debug_continue`](debug_continue.md). A finished step returns a new stop
  with `reason: "step"`. A breakpoint hit during the step can stop the target
  first.
- If the wait expires, the target keeps running.
