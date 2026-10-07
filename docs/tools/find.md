# `find`

Finds files whose paths match a glob pattern.

Defined in [`LocalTools.find()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `pattern` | string | required | Java `glob:` pattern, for example `*.java` or `src/**/Test*.java`. |
| `path` | string | `.` | Directory to search. `~/` expands to the home directory. |
| `includeIgnored` | boolean | `false` | Also return files that git ignores. |
| `limit` | integer ≥ 1 | `1000` | Maximum number of results. |

## Behaviour

- Walks every regular file under `path`, including hidden files. It always skips
  `.git` and `node_modules`.
- Unless `includeIgnored` is set, it drops files that git ignores. It finds them
  by sending the candidates to `git check-ignore --stdin -z` in the nearest
  enclosing git repository. Outside a repository, or if git can't be run,
  nothing is dropped.
- A file matches if the pattern matches either its path relative to `path` or
  just its file name. So `*.java` finds Java files at any depth.
- Results are relative paths with `/` separators, sorted case-insensitively. If
  there are more than `limit`, the output ends with `[N results limit reached]`.
- No matches returns `No files found matching pattern`.

## UI summary

`Finding <pattern> in <path>`
