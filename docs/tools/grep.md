# `grep`

Searches file contents for a regular expression or literal text.

Defined in [`LocalTools.grep()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `pattern` | string | required | Java regular expression, or literal text when `literal` is true. |
| `path` | string | `.` | **Literal** file or directory to search. Wildcards are not expanded. |
| `glob` | string | none | Only search files whose names match this glob, for example `*.java` or `src/**/*.java`. |
| `ignoreCase` | boolean | `false` | Case-insensitive (Unicode-aware) matching. |
| `literal` | boolean | `false` | Treat `pattern` as plain text. |
| `includeIgnored` | boolean | `false` | Also search files that git ignores. |
| `context` | integer ≥ 0 | `0` | Lines of context around each match. **Currently ignored:** it is advertised in the schema but the handler never reads it. |
| `limit` | integer ≥ 1 | `100` | Maximum number of matches. |

## Behaviour

- Finds files the same way as [`find`](find.md): it skips `.git` and
  `node_modules`, and skips files git ignores unless `includeIgnored` is set.
- `glob` is matched against each path relative to `path`. A glob without a `/`
  also matches bare file names. `**/` may match zero directories, so
  `src/**/*.java` also matches `src/A.java`.
- Each match is printed as `relative/path:line: text`. Lines longer than 500
  characters are cut short. Searching stops after `limit` matches and adds
  `[N matches limit reached]`.
- Files that can't be read as UTF-8 are skipped without an error.
- If `path` contains wildcard characters and doesn't exist, the error explains
  that the file pattern belongs in `glob`.
- When nothing matches, the message tells the model whether the glob selected
  any files, which helps it notice a wrong directory prefix.

The description advises the model to prefer the code-lens MCP tools for finding
symbol definitions and call sites.

## UI summary

`Searching for <pattern> in <path>`
