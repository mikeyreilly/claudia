# `debug_variables`

Shows `this`, the arguments and the visible local variables of a stopped frame.
See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `thread_id` | string | the stopped thread | Suspended thread. |
| `frame_id` | string | the top frame | A frame from [`debug_stack`](debug_stack.md). |
| `filter` | string | none | Show only variables whose names contain this text. |
| `include_sensitive_fields` | boolean | `false` | Show values whose names look sensitive (`password`, `token`, `secret` …) instead of `[REDACTED]`. |
| `limit`, `cursor` | integer | `20`, `0` | Paging. |

## Result

`variables` lists `this` first, if there is one. Each entry has:

- `name`, plus `kind` (`this`, `argument` or `local`) and `declared_type`.
- `type`, `availability` (`available`, `null` or `redacted`) and `preview`.
  Strings are previewed up to 256 characters, primitives by their value, and
  objects as `Type#id`.
- For object values, a `reference`, which you can expand with
  [`debug_object`](debug_object.md). A stop can hold up to 4,096 references.

If the class was compiled without local-variable debug information, only `this`
is shown, and the summary says so.
