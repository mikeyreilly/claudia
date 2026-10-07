# `debug_breakpoints`

Lists, adds, updates, enables, disables or removes breakpoints, including
logpoints, exception breakpoints and field watchpoints. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session to change. |
| `action` | enum | `list` | `list`, `add`, `update`, `enable`, `disable` or `remove`. |
| `breakpoints` | array (1–64) | — | Breakpoint specs, for `add` and `update`. |
| `breakpoint_ids` | string[] (1–64) | — | IDs, for `enable`, `disable` and `remove`. |
| `limit`, `cursor` | integer | `20`, `0` | Paging, for `list`. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

### Breakpoint spec fields

| Field | Applies to | Description |
| --- | --- | --- |
| `type` | all | `source` (the default), `method_entry`, `method_exit`, `exception` or `field`. |
| `breakpoint_id` | all | Your own ID (generated if omitted on `add`). Required for `update`. |
| `source_path`, `line` | `source` | Source file and 1-based line. Both required. |
| `class_name` | `method_*` and `field` (required), `source` and `exception` (optional) | Fully qualified class name. |
| `method_name`, `signature` | `method_*` (`signature` also on `source`) | Method name; `signature` is a JNI signature. It is required when the method is overloaded. |
| `exception_class` | `exception` | Only stop for this exact exception class. If omitted, every exception matches. |
| `caught`, `uncaught` | `exception` | Which exceptions to stop on. Both default to `true`. |
| `field_name`, `access`, `modification` | `field` | Field to watch. By default only modifications are watched (`access` false, `modification` true). |
| `condition` | all | Read-only expression. The target stops only when it is `true`. |
| `log_expression` | all | Turns the breakpoint into a **logpoint**: it records the value as a `logpoint` event and does not stop. |
| `hit_count`, `hit_policy` | all | `after` (stop from the Nth hit on, the default), `exact` (only the Nth hit) or `every` (every Nth hit). |
| `thread_id` | all | Only trigger on this thread. |
| `suspension_policy` | all | `all_threads` (the default) or `event_thread`. |
| `one_shot` | all | Remove the breakpoint after its first hit. |
| `enabled` | all | Defaults to `true`. |

`condition` and `log_expression` use the same restricted grammar as
[`debug_evaluate`](debug_evaluate.md).

## Behaviour

- **Atomic:** every spec is validated before any change is made. Duplicate IDs,
  ambiguous classes, overloaded methods without a `signature`, and source lines
  that resolve to more than one location are all rejected.
- **Update** merges each spec with the existing breakpoint, so give only the
  fields you want to change. The schema leaves out defaults for updates, so the
  model can't accidentally reset fields it didn't mention.
- **Pending breakpoints:** a source breakpoint whose class isn't loaded yet is
  `pending`. It is resolved when the class is prepared, and a
  `breakpoint_resolved` event is recorded. If the class loads but the breakpoint
  still can't be placed, `pending_reason` says why: missing debug information,
  a source path or method mismatch, or a line with no executable code.
  Breakpoints are **never** moved to a nearby line.
- If evaluating a condition fails, a `breakpoint_error` event is recorded and the
  target doesn't stop.
- Every change records a `breakpoint_change` event.
- There can be at most 64 specs per request and 256 breakpoints per session.

## Result

Each affected breakpoint has `breakpoint_id`, `type`, `enabled`, `hits`,
`verification` (`verified` or `pending`), `pending_reason` (if pending) and its
resolved `locations`.

## Example

```json
{"session_id":"…","action":"add",
 "breakpoints":[{"source_path":"src/main/java/com/example/Main.java","line":42}]}
```
