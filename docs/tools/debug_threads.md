# `debug_threads`

Lists the target's threads (platform and virtual) at the current stop. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `thread_id` | string | none | Show only this thread. |
| `filter` | string | none | Show only threads whose name contains this text. |
| `state` | string | none | Show only threads in this state: `running`, `waiting`, `blocked`, `sleeping`, `terminated` or `unknown`. |
| `limit`, `cursor` | integer | `20`, `0` | Paging. |

## Result

`threads` is sorted by thread ID. Each entry has:

- `thread_id`, `name`, `virtual`, `state` and `suspended`.
- `top_frame`: the location of the top stack frame, for suspended threads.
- `contended_monitor_id` and `lock_owner_thread_id`, when the thread is blocked
  on a monitor and the JVM supports reporting it.
- `owned_monitors`: up to 10 monitor IDs the thread holds, and
  `owned_monitors_truncated`.

The monitor fields make it possible to find deadlocks without calling any
methods in the target.
