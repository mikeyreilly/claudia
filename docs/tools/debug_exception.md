# `debug_exception`

Shows the exception behind the current stop, with its stack, its chain of
causes and its suppressed exceptions. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. It must have been caused by an exception breakpoint. |
| `thread_id` | string | — | Accepted for compatibility. The stopped thread is always used. |
| `depth` | integer 1–8 | `3` | Maximum number of causes to follow. |
| `limit`, `cursor` | integer | `20`, `0` | Paging for stack frames, and the page size for suppressed exceptions. |
| `suppressed_cursor` | integer ≥ 0 | `0` | Paging offset for suppressed exceptions. |

## Result

| Field | Description |
| --- | --- |
| `type` | Exception class name. |
| `message` | The `detailMessage` field, cut off at 512 characters. |
| `throw_site` | Where the exception was thrown. |
| `exception` | The exception's value, with a `reference` for [`debug_object`](debug_object.md). |
| `stack` | A page of the stopped thread's frames, with `stack_truncated` and `next_cursor`. |
| `causes` | The `cause` chain, at most `depth` long. Each cause has a reference. Cycles are detected. |
| `suppressed` | A page of suppressed exceptions, with `suppressed_truncated` and `next_suppressed_cursor`. |

The tool reads these fields directly from the target's memory. It never calls
`getMessage()` or any other method in the target.

If the current stop wasn't caused by an exception, the result is
`no_exception`.
