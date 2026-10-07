# `read`

Reads a text file, or a file inside a jar/zip archive.

Defined in [`LocalTools.read()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | string | required | File to read. Relative paths resolve against the agent workspace; `~/` expands to the home directory. |
| `offset` | integer ≥ 1 | `1` | First line to return (1-based). |
| `limit` | integer ≥ 1 | unlimited | Maximum number of lines to return. |

## Behaviour

- The file is read as UTF-8, and lines `offset` … `offset + limit - 1` are
  returned.
- Output stops at 2,000 lines or 50 KB, whichever comes first. When there is
  more, the result ends with a `Use offset=N to continue.` hint.
- An `offset` past the end of the file is an error that gives the line count.
- Missing files, directories and unreadable files produce clear errors (`File
  not found`, `Not a regular file`, `Access denied`).

### Archives

Add `!entry/path` after an archive path to read a file inside it, for example
`target/app.jar!META-INF/MANIFEST.MF`.

- `archive.jar!` or `archive.jar!some/dir/` lists the entries in that directory,
  with their sizes.
- Entry paths must not start with `/` and must stay inside the archive.
- If the entry doesn't exist, the error lists up to 20 nearby entries.
- Archives are read-only; [`write`](write.md) and [`edit`](edit.md) reject
  archive paths.

## UI summary

`Reading <path> (lines A-B)`
