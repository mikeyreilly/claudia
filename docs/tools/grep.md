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
| `context` | integer ≥ 0 | `0` | Number of lines to show before and after each match. |
| `limit` | integer ≥ 1 | `100` | Maximum number of matches. |

## Behaviour

- Finds files the same way as [`find`](find.md): it skips `.git` and
  `node_modules`, and skips files git ignores unless `includeIgnored` is set.
- `glob` is matched against each path relative to `path`. A glob without a `/`
  also matches bare file names. `**/` may match zero directories, so
  `src/**/*.java` also matches `src/A.java`.
- Each match is printed as `relative/path:line: text`. Lines longer than 500
  characters are cut short.
- Searching stops after `limit` matches, and the output ends with
  `[N matches limit reached]`. Output is limited to 2,000 lines or 50 KB.
- Files that can't be read as UTF-8 are skipped without an error.
- If `path` contains wildcard characters and doesn't exist, the error explains
  that the file pattern belongs in `glob`.
- When nothing matches, the message tells the model whether the glob selected
  any files, which helps it notice a wrong directory prefix.

The description advises the model to prefer the code-lens MCP tools for finding
symbol definitions and call sites.

## Context lines

With `context: N`, up to N lines before and after each match are shown. The
format follows grep and ripgrep:

- Match lines use colons: `path:N: text`.
- Context lines use hyphens: `path-N- text`.
- Groups that don't touch are separated by a `--` line. So are groups from
  different files.
- When windows overlap or touch, they merge into one group, and no line is
  printed twice. A match that falls inside another match's context is still
  shown and counted as a match.
- Only matches count towards `limit`. The last match's trailing context is
  still shown, even if it contains lines that would otherwise match. Those
  lines are shown as context.

```
src/A.java-41-     int total = 0;
src/A.java:42:     for (Item item : items) {
src/A.java-43-         total += item.price();
--
src/B.java-9- import java.util.List;
src/B.java:10: class B {
src/B.java-11-     List<Item> items;
```

## UI summary

`Searching for <pattern> in <path>`
