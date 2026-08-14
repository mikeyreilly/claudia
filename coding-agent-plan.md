# coding-agent improvement plan

Source: `feedback.md` (agent friction report from a real cross-repo PR-review
session on two Clojure repos, `taxamo-tax-id-checker` (Leiningen) and
`taxamo-apparatus` (tools.deps/Polylith)). This plan covers the items owned by
the **coding-agent** Java project. A companion plan, `code-lens-plan.md`,
covers the code-lens items; the two plans are independent and can be executed
by separate agents with no ordering constraints between them.

All built-in tool work is in
`src/main/java/com/quaxt/codingagent/cli/tools/BuiltInTools.java`, with tests
in `src/test/java/com/quaxt/codingagent/cli/tools/BuiltInToolsTest.java`
(JUnit Jupiter, run via `mvn -B test`).

---

## 1. Expand `~` in path arguments of all local tools

**Feedback item 1 (defect).** `read ~/wa/repo/file.clj` failed: `~` was
treated as a literal relative path and resolved against the CWD, producing
`/Users/.../cwd/~/wa/repo/file.clj`. The same `~` works in `shell` (bash
expands it), so path strings that work in one tool silently mean something
different in another.

**Where.** `LocalTool.path(String)` (BuiltInTools.java, ~line 80) is the
single choke point: `ReadTool`, `WriteTool`, `EditTool`, `GrepTool`
(via `grepRoot`), `FindTool`, and `LsTool` all resolve paths through it.

**Change.** In `LocalTool.path(String)`, before constructing the `Path`:

- `"~"` alone → `System.getProperty("user.home")`.
- A leading `"~/"` (and on Windows also `"~\\"`) → replace with the user home
  directory.
- `~user` forms (a `~` followed by anything other than `/`, `\\`, or end of
  string) are **not** supported: throw
  `IllegalArgumentException("~user paths are not supported; use an absolute path")`
  rather than silently mis-resolving.
- Everything else unchanged.

Rationale for expanding rather than rejecting plain `~/`: the feedback offers
either option, but expansion matches `shell` behavior, which is the least
surprising contract ("path strings mean the same thing in every tool").

**Tests.**

- `read` of a file addressed as `~/...` resolves to the same content as the
  absolute path (create the fixture under a temp dir and point `user.home` at
  it via `@SystemProperty`-style save/restore in the test, or compute the
  expected path from the real `user.home` and skip if unwritable — prefer the
  save/restore approach used elsewhere in the suite).
- Bare `"~"` for `ls` lists the home directory.
- `"~someuser/x"` throws with the "not supported" message.
- A file literally named `~` in the CWD: document in the tool description
  that `./~` addresses it (matching bash), and add a test.

**Doc.** Append to each path parameter description: `"A leading ~/ expands to
the user home directory."` (one sentence, keep the descriptions tight).

---

## 2. Make `grep` (and `find`) respect `.gitignore`

**Feedback item 2 (defect).** Grepping a repo for a common identifier
returned a wall of truncated JSON from `logs/marketplace-api.test.log` — a
gitignored build artifact — and burned a large share of the 50KB output
budget. Only `.git` and `node_modules` are skipped today
(`BuiltInTools.ignored(Path)`).

**Where.** `filesUnder(Path)` (used by `GrepTool`) and the walk inside
`FindTool.execute`, both funneling through `ignored(Path relative)`.

**Change.** Add gitignore awareness, default **on**, with an escape hatch:

- New helper class `GitIgnore` (same package) that, given a walk root:
  - Finds the enclosing git worktree by walking up to the first directory
    containing `.git` (file or directory — worktrees use a `.git` file).
    If none, gitignore filtering is a no-op.
  - Prefers asking git itself when available, because reimplementing
    gitignore semantics (negations, anchoring, `**`, per-directory files,
    `$GIT_DIR/info/exclude`, core.excludesFile) is a known bug farm:
    run `git -C <root> check-ignore --stdin -z` once per tool call, feeding
    the candidate relative paths collected by the walk, and drop the paths
    git reports as ignored. This is one subprocess per grep/find call,
    bounded by the same walk that already happens. Reuse the abort/timeout
    discipline from `ShellTool.execute` (poll `AbortSignal`, destroy on
    abort).
  - If `git` is not on PATH or the subprocess fails, fall back to no
    filtering (never fail the search because filtering is unavailable) and
    proceed silently — correctness of results matters more than the filter.
- Wire into `filesUnder` and `FindTool`: collect candidates as today, then
  batch-filter through `GitIgnore` before matching/reading.
- Keep the hardcoded `.git`/`node_modules` skips (they prune the walk itself,
  which the post-filter cannot).
- New optional boolean parameter `includeIgnored` (default `false`) on both
  `grep` and `find` for the rare case where searching ignored files is the
  point (e.g. inspecting build output). Mention in the description:
  `"Files ignored by git are skipped; set includeIgnored to search them."`

**Performance note.** `check-ignore --stdin -z` on a few thousand paths is
milliseconds; do not pre-filter during the walk with per-file `git` calls.

**Tests.**

- Temp git repo (`git init`, write `.gitignore` with `logs/`), file under
  `logs/` containing the pattern: default grep finds nothing, grep with
  `includeIgnored: true` finds it, find behaves the same.
- Non-git directory: behavior identical to today.
- Git missing from PATH (simulate by pointing the `GitIgnore` command at a
  bogus executable via a package-private hook): results include the ignored
  file, no exception.

---

## 3. Distinguish "glob matched no files" from "no content matches" in `grep`

**Feedback item 3.** `grep` with `glob: "src/**/*.clj"` against a repo whose
sources live under `bases/*/src/` returned `"No matches found"` — technically
true, but the useful diagnosis ("your glob selected zero files") was
invisible, forcing a fallback to `shell grep -rn`.

**Where.** `GrepTool.execute`: the loop already knows, per file, whether the
glob filter excluded it; it just doesn't count.

**Change.** Track two counters: `filesConsidered` (regular files under the
root, post-gitignore) and `filesSearched` (survived the glob filter). When
`matches == 0`:

- If a glob was given and `filesSearched == 0` and `filesConsidered > 0`:
  `"No files matched glob 'src/**/*.clj' (N files under <root> were considered). The glob is matched against paths relative to path; check the directory prefix."`
- If a glob was given and `filesSearched > 0`:
  `"No matches found in M files matching glob '...'"`
- If no glob: keep `"No matches found"` but append `" in N files"`.

Also apply the same "0 files matched" hint when the limit-reached early
return is not taken (i.e., only on the zero-match path — do not add noise to
successful searches).

**Tests.** Extend `grepFiltersFilesWithAGlobSeparateFromItsLiteralPath`-style
fixtures: wrong-prefix glob yields the "No files matched glob" message
including the considered count; right glob but absent pattern yields
"No matches found in M files".

---

## 4. Archive-aware `read` (jar/zip entries) — replaces the original item-4 verdict

**Feedback item 4, amended.** The original feedback framed this as "the agent
should have used code-lens `scope: dependencies`". Subsequent source
inspection of code-lens overturned that: code-lens dependency resolution is
Maven-`pom.xml`-only, `-sources.jar`-only, and Java-file-only, so it **could
not** have answered a lookup inside a Clojure library jar (`baselayer`) used
by a Leiningen project. The root-cause fix (Clojure dependency support) lives
in `code-lens-plan.md`. The coding-agent side of the fix is independent and
covers the long tail that no index will ever cover: make `read` (and
secondarily `find`/`grep`) able to look inside archives directly, so the
"unzip to /tmp and grep" dance is never needed again, for any ecosystem.

**Design.** Adopt the JVM-standard `!` separator convention:

```
read path: "~/.m2/repository/io/aviso/baselayer/<v>/baselayer-<v>.jar!baselayer/validation.clj"
```

- **Syntax.** Split on the **first** `!` whose prefix names an existing
  regular file (this handles the vanishingly rare `!` in directory names
  without new escaping rules; if no such split exists, treat the whole string
  as a plain path exactly as today). The suffix is the entry path inside the
  archive, always `/`-separated, no leading `/`.
- **Where.** `ReadTool.execute`: when the split applies, open with
  `FileSystems.newFileSystem(archivePath)` (JDK zipfs, already imported via
  `java.nio.file.FileSystems`), resolve the entry, and reuse the existing
  bounded read path (`readAllLines` + `truncate` + offset/limit handling)
  unchanged — the entry is just a `Path` on the zip filesystem. Close the
  filesystem in a try-with-resources.
- **Errors.**
  - Archive exists but entry doesn't: list up to ~20 entry names that share
    the entry's directory prefix (or top-level entries when none share it) in
    the error message, so a near-miss path is self-correcting in one round
    trip.
  - Suffix given but prefix is not a zip: surface the underlying
    `ProviderNotFoundException`/`IOException` with the resolved prefix path.
- **Directory listing inside archives.** If the entry suffix is empty
  (`foo.jar!`) or names a directory, return the entry listing (names, sizes)
  bounded by the usual truncation — this makes exploration one call:
  `read path: ".../baselayer-<v>.jar!"` then read the file. This is cheaper
  than teaching `ls` about archives and keeps the feature in one tool.
- **`grep`/`find` inside archives (stretch, separate commit).** When
  `GrepTool`/`FindTool` `path` points at a regular file that is a zip, walk
  the zip filesystem instead of erroring. Implement only after `read` lands;
  `read`-with-listing already covers the review workflow.
- **Write path.** `write`/`edit` must reject `!`-paths with a clear error
  ("archives are read-only through this tool").

**Doc.** `read` description gains: `"To read inside a jar/zip, append
'!entry/path' to the archive path; 'archive.jar!' lists entries."`

**Tests.**

- Build a small jar in a temp dir with `java.util.zip.ZipOutputStream`
  (no external tooling), containing `pkg/a.clj` and `pkg/b.txt`:
  - `read jar!pkg/a.clj` returns content; offset/limit work.
  - `read jar!` and `read jar!pkg/` list entries.
  - `read jar!pkg/missing.clj` error names `pkg/a.clj` and `pkg/b.txt`.
  - `edit jar!pkg/a.clj` rejects.
  - A plain file whose name contains `!` but is not an archive still reads
    (split rule: prefix must be an existing regular file).

---

## 5. Trim verbose defaults on MCP-mediated results (Jira/GitHub)

**Feedback item 5.** Default `getJiraIssue` payloads are dominated by avatar
URLs in four sizes and `self` links; bot comments add noise. These tools are
**external MCP servers** — coding-agent does not own their schemas — so the
fix here is generic result-shaping in the MCP client layer, not per-tool
hacks.

**Where.** `src/main/java/com/quaxt/codingagent/mcp/McpAgentTool.java` (tool
result path) and `McpServerConfig`/`McpConfigLoader` (configuration).

**Change (keep it modest).** Add an optional per-server config key
`resultFilters`: a list of `{ "tool": <name-or-glob>, "dropKeys": [".."] }`
entries applied recursively to JSON tool results before they are handed to
the model. Ship no defaults; document an example in the MCP configuration
docs that drops `avatarUrls` and `self` for `atlassian-mcp` tools. This keeps
coding-agent neutral (no hardcoded knowledge of Atlassian schemas) while
letting a user kill the noise once in config.

Explicitly out of scope: rewriting descriptions of external tools, filtering
bot comments (content-level, belongs to the user's prompt or the server).

**Tests.** `McpManagerTest`-adjacent unit test: a fake tool result JSON with
nested `avatarUrls`/`self` keys, config with `dropKeys`, assert removal and
that non-JSON (plain text) results pass through untouched.

---

## 6. Workflow-level: nudge toward semantic tools when available

**Feedback "One workflow-level suggestion".** The longest phase of the review
was stitching referenced-but-unchanged code around diff hunks; the agent
under-used `code-lens_context` because generic tools were "almost good
enough".

This is a prompt/description concern, not a code feature. Two small,
low-risk changes:

- In the built-in `grep` description, append: `"For symbol definitions and
  call sites in indexed repositories, a code-lens context/query tool (when
  connected) is usually faster and resolves aliases."` Only do this if the
  team is comfortable with cross-referencing an optional MCP server from a
  built-in description; otherwise skip — do not invent a plugin mechanism
  for this.
- No other changes. The heavier ideas from the feedback ("diff with
  resolvable context") are intentionally deferred: item 4 here plus the
  code-lens plan remove most of the demonstrated cost, and a new diff tool
  is speculative scope.

---

## Suggested sequencing

| Order | Item | Size | Risk |
|---|---|---|---|
| 1 | Tilde expansion (item 1) | S | none — pure addition at one choke point |
| 2 | Grep zero-file diagnostics (item 3) | S | none — message-only |
| 3 | Archive-aware read (item 4) | M | low — new parse rule guarded by "prefix must be an existing file" |
| 4 | Gitignore filtering (item 2) | M | medium — subprocess dependency, needs the fallback path |
| 5 | MCP result filters (item 5) | M | low — config-gated, off by default |
| 6 | Description nudge (item 6) | XS | judgment call, do last or drop |

Items are independent; 1–3 are safe to batch in one PR, 4 and 5 should each
be their own PR.

Definition of done for every item: `mvn -B test` green, new behavior covered
by `BuiltInToolsTest` (or the MCP test suite for item 5), and tool
descriptions updated in the same commit as the behavior they describe.
