# `debug_stack`

Lists the stack frames of a suspended thread. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `thread_id` | string | the stopped thread | Suspended thread whose stack to show. |
| `filter` | string | none | Keep only frames whose declaring class name contains this text. |
| `include_library_frames` | boolean | `true` | If `false`, drop frames from `java.*`, `javax.*`, `jdk.*`, `sun.*` and `com.sun.*`. |
| `include_arguments` | boolean | `false` | Add up to 8 argument values to each frame. Arguments whose names look sensitive are redacted. |
| `limit`, `cursor` | integer | `20`, `0` | Paging. |

## Result

`frames` is listed from the top of the stack down. Each frame has its location
(class, method, source path and line) and a `frame_id` in the form
`<stop_id>:<thread_id>:<index>`. Pass the `frame_id` to
[`debug_variables`](debug_variables.md), [`debug_source`](debug_source.md) or
[`debug_evaluate`](debug_evaluate.md). Frame IDs are valid only for the current
stop.

If a frame's class has no local-variable debug information, it has
`arguments_unavailable: true`.
