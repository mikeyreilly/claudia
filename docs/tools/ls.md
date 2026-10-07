# `ls`

Lists the contents of a directory.

Defined in [`LocalTools.ls()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | string | `.` | Directory to list. Relative paths resolve against the agent workspace; `~/` expands to the home directory. |
| `limit` | integer ≥ 1 | `500` | Maximum number of entries. |

## Behaviour

- Lists the directory's direct children only (it does not recurse).
- Entries are sorted case-insensitively, and directories end with `/`.
- Hidden files are included, and `.gitignore` is not applied.
- An empty directory returns `(empty directory)`. A path that isn't a directory
  is an error.
- Output is limited to 2,000 lines or 50 KB.

## UI summary

`Listing <path>`
