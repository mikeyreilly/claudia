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
- The project's dependency tool plus a JDK when indexing dependency sources:
  Maven/`mvnw`, Leiningen, or the Clojure CLI

### Windows

- Clang with ISO C23 support on `PATH` (the native `x86_64-pc-windows-msvc`
  build is supported)
- Git for Windows, `curl.exe`, `tar.exe`, and Windows PowerShell
- The project's dependency tool plus a JDK when indexing dependency sources:
  Maven/`mvnw`, Leiningen, or the Clojure CLI
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

## Dependency sources

Code-lens detects one root build file in priority order:

1. `pom.xml`: invoke the project's `mvnw`/`mvnw.cmd` when present, otherwise
   `mvn`, and use the pinned Maven Dependency Plugin to copy source JARs for
   the effective test classpath.
2. `project.clj`: invoke `lein classpath` and inspect its resolved JARs.
3. `deps.edn`: invoke `clojure -Spath` and inspect its resolved JARs.

Maven applies the project's normal parent POMs, dependency management,
profiles, mirrors, credentials, proxies, and reactor rules. Leiningen and
`tools.deps` use their default profile/alias classpaths. Supply
`--aliases :dev:reporting` to `index` to resolve a tools.deps basis through
those aliases; code-lens invokes `clojure -Spath -M:dev:reporting` and stores
the alias selection in dependency metadata, so a changed selection refreshes
the dependency index. Direct `query`, `context`, and `sql` accept the same
option for their dependency-staleness check. MCP exposes `aliases` on every
read tool and transparently prepares the matching dependency index. The setting
is ignored for Maven and Leiningen. For classpath JARs, code-lens prefers a
sibling `-sources.jar`. During tools.deps resolution, it asks the Clojure CLI to
fetch missing Maven `artifact$sources` classifiers in bounded batches, retaining
main-JAR fallback for artifacts whose sources are unavailable. When no source
JAR is available it extracts supported Clojure and Java source files from the
main JAR, as is customary for Clojure libraries.

Extracted sources are materialized under
`$CODE_LENS_HOME/dependencies/sources/<group>/<artifact>/<version>/<checksum>/`.
Those paths are stable and can be passed directly to `read` or `grep` tools.
The cache is shared by checksum; each repository index records the exact GAV
coordinates and files in `DependencyArtifact` and `DependencyFile`. Maven
reactors currently use a reactor-wide union with scope `test-classpath`;
Leiningen/tools.deps entries use scope `classpath`. In both cases `modulePath`
is the repository root and dependency directness is unknown (`direct = -1`).
The legacy `MavenProject.rootPom` column stores whichever root build file was
detected (`pom.xml`, `project.clj`, or `deps.edn`).

`query` remains workspace-only by default. Use `--scope dependencies` (or MCP
`scope: "dependencies"`) to search dependency definitions, `--scope all` to
search both, and `--dependency 'org.jline:*'` to filter by a GAV glob. Results
include their origin, coordinate, classpath scope, and materialized source
path. `context` first searches workspace definitions, then automatically falls
back to dependency definitions while keeping workspace call sites first. A
workspace source-file `path` can be supplied as calling context when there is
no definition in that file. Repositories without a recognized root build file
record a `none` dependency status, so dependency-scoped empty results explain
why no dependency files are available.

Code-lens snapshots all reactor `pom.xml` files, local parent POMs, Maven
wrappers, and `.mvn/**`; for Leiningen/tools.deps it snapshots the root
`project.clj`/`deps.edn`. Ordinary source refreshes reuse the prior dependency
set. Resolution runs again when those inputs change, the materialized cache is
missing, or a prior `failed`/`disabled` generation becomes retryable. A build
tool failure never discards a published dependency-source set. On a first
failure it produces a workspace-only partial index and a warning; because that
generation has no dependency rows to preserve, later workspace refreshes can
safely publish another current workspace-only generation while resolution
continues to be retried by `index`.

`CODE_LENS_MAVEN=0` is the global dependency-resolution kill switch for Maven,
Leiningen, and tools.deps; `CODE_LENS_MAVEN_TIMEOUT_MS` changes the shared
120-second timeout. Hermetic installations can override executables with
`CODE_LENS_MAVEN_COMMAND`, `CODE_LENS_LEIN_COMMAND`, and
`CODE_LENS_CLOJURE_COMMAND`. Automatic dependency resolution is enabled by
default and build tools may execute project code, plugins, or extensions.
Index only repositories you trust, or set `CODE_LENS_MAVEN=0` before indexing
or starting MCP.

## CLI at a glance

```
code-lens help
code-lens version
code-lens index --repo <path> [--aliases :a[:b...]]
code-lens remove --repo <path>
code-lens list
code-lens query --repo <path> [--aliases :a[:b...]] [--scope workspace|dependencies|all]
                [--dependency <gav-glob>] "<terms>"
code-lens context --repo <path> --name <symbol> [--aliases :a[:b...]] [--exclude-tests]
                   [--namespace <namespace>] [--path <substring>]
code-lens sql --repo <path> [--aliases :a[:b...]] "<query>"
code-lens mcp
```

`code-lens mcp` runs a JSON-RPC 2.0 MCP server on stdio exposing three semantic
tools: `query`, `context`, and `sql`. Their optional `repo` argument accepts an
absolute repository root or any file or directory inside a non-bare Git
worktree. When supplied, `repo` must be absolute; omitting it uses the
directory where the code-lens server was started, which is not necessarily the
client's working directory. Code-lens prepares current search data
transparently before each request. Index inventory, freshness, and cache
maintenance are deliberately absent from the model-facing interface; use the
`index`, `list`, and `remove` CLI commands for administration. See `USAGE.txt`
for client registration examples.

`query` searches Clojure, Java, and C symbol definitions plus Clojure keyword
usage locations. Symbol results match whole words and word prefixes (names are split
on `- . / _`), ranked by BM25; keyword results match keyword-name prefixes such
as `:report!-fn`. Keyword locations are distributed across matching files before
repeated occurrences from one file, so a noisy file cannot consume a small result
limit. When additional keyword matches are omitted, the output suggests narrowing
with `path`/`--path` or increasing `limit`. It does not match arbitrary mid-word
substrings. Java kinds
include `class`, `interface`, `enum`, `annotation`, `record`, `constructor`,
`method`, `field`, `enum_constant`, and `test`; C adds `struct`, `union`,
`typedef`, and `variable` while sharing `function`, `macro`, and related kinds.
Multi-term queries require every term to match; when that finds nothing the search relaxes
to any-term matches and flags the result with a notice line. Use
`--exclude-tests`, `--kind <kind>`, and `--path <substring>` to cut irrelevant
matches; `path` is a case-insensitive substring of result file paths and is especially
useful for common keywords in large repositories. Symbol kinds filter symbols only
and suppress keyword rows, while
`--kind keyword` returns keyword rows only. `context` normally shows
definitions, reference call sites, and lazy source snippets for a symbol name.
When the selected Java symbol is a class, interface, enum, annotation, or
record, it instead returns a bounded **Class Dossier**: the declaration and
member inventory, small method bodies (large methods are signatures), direct
hierarchy, override relationships, only behaviorally relevant inherited
methods, supporting implementation types, and representative
construction/call/field/type-check/test usages ranked for explanatory value.
No separate tool or option is required. For non-type targets,
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
appeared. Explicit CLI and public-API reads retain their non-mutating behavior:
they return any stale results with a warning. MCP instead guarantees that
`query`, `context`, and `sql` operate on current worktree data or return an
ordinary search-availability error. A current workspace-only partial index
remains available when dependency resolution fails, so missing resolver
credentials do not make workspace search unavailable. MCP `sql` remains
read-only with respect to user SQL.

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
