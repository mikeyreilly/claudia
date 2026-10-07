# `debug_sessions`

Lists this agent's debug sessions. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `limit` | integer 1–100 | `20` | Page size. |
| `cursor` | integer ≥ 0 | `0` | Paging offset. |

## Result

`sessions` holds one entry per session, sorted by session ID. Each entry is the
full [`debug_status`](debug_status.md) for that session. It covers sessions in
every state, including completed ones that haven't been closed with
`debug_detach close_session: true`. If there are more sessions than `limit`,
the result has `truncated` and `next_cursor`.

Use it to recover a `session_id` you no longer have.
