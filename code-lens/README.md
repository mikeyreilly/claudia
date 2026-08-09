# code-lens

A native ISO C23 command-line tool and MCP server that indexes Clojure, Java,
and C codebases into per-repo [SQLite](https://sqlite.org) databases and answers
symbol search (FTS5 full-text search ranked with BM25), definition/reference
context, and raw SQL queries over the index.

One binary serves both the CLI and the MCP stdio server. The entire
application lives in a single C file (`src/code_lens.c`) with one public
header, built by a plain Makefile. The toolchain is pure C end to end:
SQLite is vendored as the C amalgamation and the final binary links with the
C compiler.

## Requirements

### macOS and Linux

- A C compiler with ISO C23 (or C2x) support
- `make`, `git`, `curl`, `unzip`, and a POSIX shell
- Maven (or a project Maven wrapper) plus a JDK when indexing Maven dependency sources

### Windows

- Clang with ISO C23 support on `PATH` (the native `x86_64-pc-windows-msvc`
  build is supported)
- Git for Windows, `curl.exe`, `tar.exe`, and Windows PowerShell
- Maven (or a project Maven wrapper) plus a JDK when indexing Maven dependency sources
- A Windows SDK/MSVC runtime discoverable by Clang

No MSYS2 shell, `make`, or separately installed zlib is needed for the native
Windows build. The dependency bootstrap vendors zlib there along with SQLite,
tree-sitter, and libdeflate.

## Quick start

```sh
# 1. Fetch vendored dependencies (required on a clean checkout).
scripts/vendor-deps.sh

# 2. Build the release binary (copied to build/code-lens).
make release

# 3. Run the tests.
make test

# 4. Index a Clojure, Java, C, or mixed repository and query it.
CODE_LENS_HOME=/tmp/code-lens-home \
  ./build/code-lens index --repo /path/to/repo

CODE_LENS_HOME=/tmp/code-lens-home ./build/code-lens query --repo /path/to/repo "http handler"
```

On Windows, run the equivalent commands from `cmd.exe` or PowerShell:

```bat
rem 1. Fetch dependencies.
scripts\vendor-deps.bat

rem 2. Build build\code-lens.exe with the Clang on PATH.
build.bat release

rem A debug build is also available.
build.bat debug

rem 3. Use it.
build\code-lens.exe index --repo C:\path\to\repo
build\code-lens.exe query --repo C:\path\to\repo "http handler"
```

`build.bat clean` removes generated build artifacts, and
`build.bat vendor-deps` is a shortcut for the batch dependency bootstrap.

Each repo's index is stored at `$CODE_LENS_HOME/repos/<repo-dir>/index.sqlite`
(default home `~/.code-lens`). Re-indexing or removing a repo touches only
that repo's index; a persistent advisory lock under
`$CODE_LENS_HOME/repos/.locks/` serializes concurrent indexing, automatic
refresh, and removal across processes. Use an isolated `CODE_LENS_HOME` for
experiments so you do not touch your default index.

Explicit CLI commands and public APIs identify a repository by path alone.
Their paths are canonicalized (with `~/` expansion); they retain support for
plain directories, and any path inside an indexed repository resolves to that
repository, so `--repo` accepts the repo root or any file or directory within
it.

Supported source extensions are `.clj`, `.cljc`, `.cljs`, `.bb`, `.java`,
`.c`, and `.h`. The Java extractor uses tree-sitter-java and indexes packages,
imports, types, members, Javadocs, and resolved references. The C extractor
uses tree-sitter-c and indexes functions/prototypes, variables, typedefs,
structs, unions, enums, fields, macros, documentation comments, calls, and
member accesses. Repositories can mix all three languages.

## Maven dependency sources

When a repository root contains `pom.xml`, indexing transparently invokes the
project's `mvnw`/`mvnw.cmd` when present, otherwise `mvn`, to resolve source
JARs for the effective test classpath. The Maven Dependency Plugin version is
pinned by code-lens. Maven therefore applies the project's normal parent POMs,
dependency management, profiles, mirrors, credentials, proxies, and reactor
rules rather than code-lens attempting to reproduce model resolution.

Source JARs are materialized under
`$CODE_LENS_HOME/dependencies/sources/<group>/<artifact>/<version>/<checksum>/`.
Those paths are stable and can be passed directly to `read` or `grep` tools.
The extracted source cache is shared by checksum; each repository index records
the exact Maven coordinates and files on its resolved classpath in
`DependencyArtifact` and `DependencyFile`. Multi-module reactors currently use a
reactor-wide union: `modulePath` is the repository root, dependency directness is
unknown, and the recorded scope is `test-classpath`.

`query` remains workspace-only by default. Use `--scope dependencies` (or MCP
`scope: "dependencies"`) to search dependency definitions, `--scope all` to
search both, and `--dependency 'org.jline:*'` to filter by a Maven GAV glob.
Results include their origin, coordinate, classpath scope, and materialized
source path. `context` first searches workspace definitions, then automatically
falls back to Maven dependency definitions while keeping workspace call sites
first. A workspace source-file `path` can be supplied as calling context when
there is no definition in that file.

Code-lens snapshots all reactor `pom.xml` files, local parent POMs, Maven
wrappers, and `.mvn/**`. Ordinary source refreshes reuse the prior dependency
set; Maven runs again when those inputs change, the materialized cache is
missing, or a prior `failed`/`disabled` generation becomes retryable. A Maven
failure never replaces an existing published index. On a first index it
produces a workspace-only partial index and a warning. Set
`CODE_LENS_MAVEN=0` to disable Maven execution or
`CODE_LENS_MAVEN_TIMEOUT_MS` to change the 120-second timeout. Maven execution
is enabled by default. Because Maven may execute build extensions and plugins,
index only repositories you trust; set `CODE_LENS_MAVEN=0` before indexing or
starting MCP for untrusted code.

## CLI at a glance

```
code-lens help
code-lens version
code-lens index --repo <path>
code-lens remove --repo <path>
code-lens list
code-lens query --repo <path> [--scope workspace|dependencies|all]
                [--dependency <gav-glob>] "<terms>"
code-lens context --repo <path> --name <symbol> [--exclude-tests]
                   [--namespace <namespace>] [--path <substring>]
code-lens sql --repo <path> "<query>"
code-lens mcp
```

`code-lens mcp` runs a JSON-RPC 2.0 MCP server on stdio exposing five tools:
`list_repos`, `query`, `context`, `sql`, and `remove_repo`. For MCP
`query`, `context`, and `sql`, `repo` must be the exact root of a Git
worktree—not a subdirectory, file, or plain directory. These self-maintaining
tools automatically build a missing index, repair an unreadable or obsolete
one, and refresh a stale one before reading. `list_repos` is observational and
never changes indexes. `remove_repo` can evict an orphaned index by its stored
repo path. See `USAGE.txt` for client registration examples.

`query` searches Clojure, Java, and C symbol definitions plus Clojure keyword
usage locations. Symbol results match whole words and word prefixes (names are split
on `- . / _`), ranked by BM25; keyword results match keyword-name prefixes such
as `:report!-fn`. It does not match arbitrary mid-word substrings. Java kinds
include `class`, `interface`, `enum`, `annotation`, `record`, `constructor`,
`method`, `field`, `enum_constant`, and `test`; C adds `struct`, `union`,
`typedef`, and `variable` while sharing `function`, `macro`, and related kinds.
Multi-term queries require every term to match; when that finds nothing the search relaxes
to any-term matches and flags the result with a notice line. Use
`--exclude-tests`, `--kind <kind>`, and `--path <substring>` to cut irrelevant
matches; symbol kinds filter symbols only and suppress keyword rows, while
`--kind keyword` returns keyword rows only. `context` shows
definitions, reference call sites, and lazy source snippets for a symbol name;
references include alias-qualified and fully-qualified uses resolved through
each file's require aliases, plus unqualified uses in files that `:refer` or
`:use` the symbol in (or define it themselves), and a qualifier in the given
name (for example `str/join`) is ignored for the lookup. `context` also
accepts `--exclude-tests` (`excludeTests` over MCP), `--namespace <namespace>`
(`namespace` over MCP), and `--path <substring>` (`path` over MCP).
Namespace and path filters normally select matching definition candidates, so only
references that resolve to those candidates are shown. When workspace lookup
finds no definition and `path` identifies a workspace calling file, dependency
fallback treats it as source context instead of filtering the external source path. For Java, normal imports, static imports,
enclosing types, and declared receiver types are resolved without running a
compiler; calls whose receiver type requires whole-program type inference may
remain unresolved. Java definitions use the fully qualified enclosing type as
`namespace` (for example `com.example.Service`). For C, externally linked
symbols use the empty namespace, static symbols use their file path, and fields
use their aggregate type. This resolves ordinary cross-file calls, file-local
calls, global variables, and typed `.`/`->` member accesses without running a
compiler. `context` accepts Java `Type.member`, `Type#member`, fully qualified
type spellings, and C `ptr->field` spellings as well as a bare name.
`--exclude-tests` drops test-file rows. `sql` runs read-only SQL against the
chosen repo's database (reads only -- ATTACH, PRAGMA, and writes are denied),
with output capped at 100 rows.

`list`, `query`, and `context` check whether indexed files still match their
recorded size/mtime snapshot and whether new Clojure, Java, or C files have
appeared.
Explicit CLI and public-API reads retain their non-mutating behavior: they
return any stale results with a warning. In contrast, MCP `query`, `context`,
and `sql` maintain an exact-root Git-worktree index before reading: they build,
repair, or refresh it atomically as needed. `list_repos` remains observational;
MCP `sql` remains read-only with respect to user SQL.

## Documentation

| Document | Contents |
| --- | --- |
| [`developer.md`](developer.md) | The full developer reference: build system, source architecture, memory model, parser, database schema, indexing pipeline, threading, testing, benchmarking, and the invariants to preserve. |
| [`USAGE.txt`](USAGE.txt) | End-user CLI usage, smoke-test examples, and MCP registration. |
| [`AGENTS.md`](AGENTS.md) | Condensed build and convention notes for AI coding agents. |

## Project rules in brief

- Project-owned sources are ISO C23, compiled with
  `-Wall -Wextra -Wpedantic -Werror -pedantic-errors`.
- Application queries always bind parameters; user input is never
  interpolated into SQL text.
- Vendored dependency source trees are local artifacts (git-ignored), pinned
  by `vendor/deps.lock` (git commits for source trees, a SHA-256 for the
  SQLite amalgamation archive).

See [`developer.md`](developer.md) for the complete list of invariants.
