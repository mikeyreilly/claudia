# `debug_source`

Shows a short window of source code around a stopped frame's line, or around a
line you choose. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `session_id` | string | required | Session. |
| `stop_id` | string | required | The current stop. |
| `thread_id` | string | the stopped thread | Suspended thread. |
| `frame_id` | string | the top frame | A frame from [`debug_stack`](debug_stack.md). |
| `source_path` | string | auto | Source file to read. If omitted, the frame's package-relative source path is looked up under `src/main/java` and `src/test/java`. |
| `line` | integer ≥ 0 | `0` | Line to centre on. `0` means the frame's current line. |
| `before`, `after` | integer 0–30 | `3`, `3` | Number of lines to show before and after `line`. |

## Behaviour

- The file must match the frame's source file name and package-relative path.
  Otherwise the result is `source_mismatch`. If more than one file matches, it
  is `ambiguous_source`.
- Files larger than 256 KB are refused. Each line is cut off at 512 characters.
- If `line` is past the end of the file, the result is `source_mismatch`. This
  usually means the source changed after it was compiled.

## Result

`source_path`, `line`, `location_source_path` and `lines` (a list of
`{line, text}`). `truncated` is true when the window doesn't cover the whole
file. `source_bytecode_verified` is always `false`: the tool doesn't check that
the file on disk matches the loaded class.
