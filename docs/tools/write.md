# `write`

Creates or overwrites a text file.

Defined in [`LocalTools.write()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java).

## Parameters

| Name | Type | Description |
| --- | --- | --- |
| `path` | string | File to write. Relative paths resolve against the agent workspace; `~/` expands to the home directory. |
| `content` | string | The complete new contents of the file. |

## Behaviour

- Creates any missing parent directories.
- Writes `content` as UTF-8 and replaces any existing contents.
- Returns `Successfully wrote N bytes to <path>`.
- Rejects paths inside an archive (`foo.jar!entry`).
- Checks the abort signal before and after writing.

Use [`edit`](edit.md) to change part of an existing file.

## UI summary

`Writing <path> (N characters)`
