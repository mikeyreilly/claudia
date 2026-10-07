# `debug_object`

Expands an object reference from the current stop by one level. You can page
through its fields, array elements or string characters. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `reference` | string | required | A `reference` returned by [`debug_variables`](debug_variables.md), [`debug_stack`](debug_stack.md), [`debug_evaluate`](debug_evaluate.md) or an earlier `debug_object` call **at this stop**. |
| `start` | integer ≥ 0 | `cursor` | First field, element or character to return. |
| `limit` | integer 1–100 | `20` | Number of fields or elements. |
| `cursor` | integer ≥ 0 | `0` | Another name for `start`. |
| `include_inherited` | boolean | `true` | Include fields declared in superclasses. |
| `include_static` | boolean | `false` | Include static fields. |
| `include_sensitive_fields` | boolean | `false` | Show fields whose names look sensitive instead of redacting them. |
| `depth` | integer | `1` | Only `1` is supported. Anything higher returns `unsupported`; follow references one level at a time. |
| `object_id`, `frame_id`, `thread_id`, `length` | — | — | Accepted for compatibility. The object is identified by `reference` alone. |

## Result

The result has `type` and `object_id`, plus:

- **Strings:** `value`, a slice of up to `min(limit, 1024)` characters, and
  `length`.
- **Arrays:** `fields`, where each element has an `index` and a value, and
  `length`.
- **Other objects:** `fields`, where each field has a `name`,
  `declared_type`, `declaring_class` and value. Object values have their own
  `reference`.

`truncated` and `next_cursor` show when there is more. A reference from an
earlier stop is rejected with `stale_reference`.

Generic collections are not walked specially; you see a `HashMap`'s actual
internal fields. Expand them one level at a time.
