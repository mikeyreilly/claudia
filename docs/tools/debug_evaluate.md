# `debug_evaluate`

Evaluates one read-only expression, or a batch of up to 20, in a stopped frame.
See [debugger concepts](debugger.md).

Expressions are parsed by
[`ReadOnlyExpression`](../../src/main/java/com/quaxt/claudia/debug/ReadOnlyExpression.java).
It is a small inspection language, not a Java interpreter, and it never calls
methods or changes the target.

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `thread_id` | string | the stopped thread | Suspended thread. |
| `frame_id` | string | the top frame | A frame from [`debug_stack`](debug_stack.md). |
| `expression` | string | — | One expression, at most 256 characters. Give exactly one of `expression` and `expressions`. |
| `expressions` | string[] (1–20) | — | A batch of expressions, each at most 256 characters. |
| `timeout_ms` | integer 0–30000 | `0` | Time budget for the batch: once it runs out, no further expressions are started. It cannot interrupt a JDI read that is already running. `0` means no limit. |
| `allow_side_effects` | boolean | `false` | Not implemented. `true` returns `unsupported`. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Grammar

| Supported | Examples |
| --- | --- |
| Local variables and `this` | `count`, `this` |
| Field paths | `items[0].provider`, `this.config.name` |
| Array indexing, including with a local variable as the index, and nested arrays | `numbers[index]`, `matrix[1][2]` |
| Array length | `matrix[0].length` |
| Literals | `42`, `-1.5`, `true`, `null`, `"escaped \"string\""` |
| One comparison | `==`, `!=`, `<`, `<=`, `>`, `>=`, for example `model.provider == "openai"` |

- Strings are compared by content, other objects by identity, and numbers and
  booleans by value.
- String literals stay in the debugger; they are never created in the target.
- Expressions may nest at most 16 subscripts.
- **Not supported:** method calls, assignment, arithmetic, `&&`/`||`, casts, or
  anything else that is general Java.

Breakpoint `condition` and `log_expression` use the same grammar.

## Result

- **Single expression:** `expression` and `value`. The value has the same form
  as in [`debug_variables`](debug_variables.md), including a `reference` for
  objects. Any error fails the whole call.
- **Batch:** `results` in the same order as the input. Each result has
  `status`, `expression`, and either a `value` or an error `code` and
  `message`. There is also an `error_count`. One bad expression does not discard
  the others.

All results include `safety_level: "read_only"`.

## Example

```json
{"session_id":"…","stop_id":"…",
 "expressions":["numbers.length","numbers[0]","model.provider == \"openai\""]}
```
