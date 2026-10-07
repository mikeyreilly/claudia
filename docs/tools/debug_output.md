# `debug_output`

Returns the stdout and stderr captured from a launched target, starting after a
cursor. This works even after the target has exited. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `cursor` | integer ≥ 0 | `0` | Return output segments after this cursor. |
| `stream` | string | both | `stdout` or `stderr`. |
| `limit` | integer 1–100 | `20` | Maximum number of segments. |
| `byte_limit` | integer 1–51200 | `51200` | Maximum UTF-8 bytes of text. |
| `line_limit` | integer 1–2000 | `2000` | Maximum number of lines. |
| `suppress_output` | boolean | `false` | Replace each segment's text with `[suppressed]`. Use it to advance the cursor without reading the text. |

## Result

- `output`: segments, each with a `cursor`, `stream` and `text`.
- `cursor`, `gap`, `truncated` and `next_cursor`, as in
  [`debug_events`](debug_events.md).
- `output_complete`: the target's output streams have closed.
- `sensitive_data_warning`, plus `output_warning` if capture was limited.

Values of sensitive environment variables are redacted from the captured text.
If the very next segment is bigger than `byte_limit` or `line_limit`, the call
returns `limit_too_small`.

## Unsupported

- **Attached** sessions: JDI cannot capture an attached JVM's output.
- Sessions launched with `capture_output: false`, or where capture was turned
  off for safety.
