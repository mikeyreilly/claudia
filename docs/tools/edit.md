# `edit`

Replaces one or more exact blocks of text in a file.

Defined in [`LocalTools.edit()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java).

## Parameters

| Name | Type | Description |
| --- | --- | --- |
| `path` | string | File to edit. Relative paths resolve against the agent workspace; `~/` expands to the home directory. |
| `edits` | array (at least one item) | Replacements to make. Each item has `oldText` (the exact text to find) and `newText` (its replacement). |

## Behaviour

1. Reads the file as UTF-8.
2. Finds each `oldText` in the **original** contents. Each one must match
   exactly once:
   - no match is an error: `oldText was not found`.
   - more than one match is an error: `oldText must match exactly one location`.
3. Sorts the matches by position and rejects any that overlap.
4. Applies the replacements from the end of the file backwards, so earlier
   positions stay valid, then writes the file.

The edit is all-or-nothing: if any replacement is invalid, the file is left
unchanged. Paths inside an archive are rejected.

Returns `Successfully replaced N block(s) in <path>`.

## UI summary

`Editing <path> (N replacement(s))`
