# code-lens developer guide

This document is the self-contained developer reference for code-lens. It
covers what the project is, how to build and test it, how every subsystem
works internally, and the invariants that must be preserved when changing it.
Line references are anchors into the source at the time of writing; line
numbers drift, so prefer searching for the named function when in doubt.

Related documents: `README.md` (project overview and quick start),
`USAGE.txt` (end-user CLI usage), and `AGENTS.md`
(condensed instructions for AI coding agents). This guide supersedes none of
them; it is the full-depth companion to all three.

## Table of contents

1. [What code-lens is](#1-what-code-lens-is)
2. [Quick start](#2-quick-start)
3. [Repository layout](#3-repository-layout)
4. [Build system](#4-build-system)
5. [Vendored dependencies](#5-vendored-dependencies)
6. [Source architecture](#6-source-architecture)
7. [Memory model: the arena allocator](#7-memory-model-the-arena-allocator)
8. [Platform helpers and file walking](#8-platform-helpers-and-file-walking)
9. [Language parsers](#9-language-parsers)
10. [Database layer and schema](#10-database-layer-and-schema)
11. [The indexing pipeline](#11-the-indexing-pipeline)
12. [Indexing parallelism](#12-indexing-parallelism)
13. [Search and query semantics](#13-search-and-query-semantics)
14. [CLI reference](#14-cli-reference)
15. [MCP server](#15-mcp-server)
16. [Testing](#16-testing)
17. [Benchmarking and performance workflow](#17-benchmarking-and-performance-workflow)
18. [Environment variables](#18-environment-variables)
19. [Invariants checklist](#19-invariants-checklist)

## 1. What code-lens is

code-lens is a native ISO C23 command-line tool and MCP (Model Context
Protocol) server that indexes Clojure, Java, and C codebases into per-repo SQLite
databases and answers symbol search (FTS5 full-text search ranked with
BM25), definition/reference context, and raw SQL queries over that index.

- One binary: `build/code-lens`. The same binary serves the CLI and the MCP
  stdio server.
- One implementation file: `src/code_lens.c` (roughly 18,000 lines), plus one public
  header, `include/code_lens.h`.
- Project-owned code is C, compiled as ISO C23 with pedantic diagnostics.
  The toolchain is pure C end to end: SQLite is vendored as the C
  amalgamation, compiled alongside the project, and the final binary links
  with the C compiler against libSystem only.
- Each repo's index is a single SQLite file,
  `<home>/repos/<repo-dir>/index.sqlite`, built in a staging file and
  published with an atomic rename. A per-repository `fcntl` advisory lock in
  `<home>/repos/.locks/` serializes index/refresh/remove writers across
  processes.

## 2. Quick start

```sh
# 1. Bootstrap vendored dependencies (required on a clean checkout).
scripts/vendor-deps.sh

# 2. Build the release binary (copied to build/code-lens).
make release

# 3. Run the tests.
make test

# 4. Index a Clojure, Java, C, or mixed repository into an isolated home and query it.
CODE_LENS_HOME=/tmp/code-lens-home \
  ./build/code-lens index --repo /path/to/clojure-repo

CODE_LENS_HOME=/tmp/code-lens-home ./build/code-lens list
CODE_LENS_HOME=/tmp/code-lens-home ./build/code-lens query --repo /path/to/clojure-repo "http handler"
CODE_LENS_HOME=/tmp/code-lens-home ./build/code-lens context --repo /path/to/clojure-repo --name my-fn
CODE_LENS_HOME=/tmp/code-lens-home ./build/code-lens sql --repo /path/to/clojure-repo \
  "SELECT name, kind FROM Symbol"
```

Always use an isolated `CODE_LENS_HOME` for experiments, smoke tests, and
profiling. Without it, commands operate on the per-repo indexes under the
default `~/.code-lens/repos/`.

## 3. Repository layout

| Path | Contents |
| --- | --- |
| `src/code_lens.c` | The entire application implementation, organized into sections (see [section map](#6-source-architecture)). |
| `include/code_lens.h` | The single public header. Also consumed by the tests. |
| `tests/test_main.c` | Test entry point (`main`), sanity checks, and the index/readback suite. |
| `tests/test_clojure_parser.c` | Clojure parser assertions against an inline fixture. |
| `tests/test_java_support.c` | Java parser and end-to-end indexing/context assertions. |
| `tests/test_c_support.c` | C parser, linkage/member resolution, and end-to-end indexing assertions. |
| `scripts/vendor-deps.sh` | Fetches and pins vendored dependencies (git clones and the SQLite amalgamation archive). |
| `scripts/detect-c23-flag.sh` | Probes the compiler for `-std=c23` vs `-std=c2x`. |
| `scripts/benchmark-index.sh` | Repeatable indexing benchmark harness. |
| `vendor/deps.lock` | Pinned dependency refs (name, url, ref-or-sha256, license). |
| `vendor/sqlite/`, `vendor/tree-sitter/`, `vendor/tree-sitter-clojure/`, `vendor/tree-sitter-java/`, `vendor/tree-sitter-c/` | Dependency trees. Local artifacts, git-ignored. |
| `Makefile` | The whole build. No CMake anywhere. |
| `README.md` | Project front door: overview, quick start, doc links. |
| `USAGE.txt` | End-user CLI usage and MCP registration notes. |
| `AGENTS.md` | Condensed build/convention notes for AI coding agents. |
| `build/` | Generated. Objects and binaries. Git-ignored. |

There is no CI configuration; `.github/` is empty. Nothing runs `make test`
automatically. Native Windows builds use `build.bat` plus
`scripts/vendor-deps.bat`; `src/windows_compat.c` is the private Win32
filesystem/threading compatibility layer. The documentation set is `README.md` (overview and quick
start), this file (the deep reference), `USAGE.txt`, and `AGENTS.md`.

## 4. Build system

The build is a single `Makefile` driving the C compiler for project and
vendor sources. There is no CMake and no C++ anywhere.

### Targets

| Target | Effect |
| --- | --- |
| `all` (default) | Build `build/$(BUILD)/code-lens` and copy it to `build/code-lens`. |
| `release` | `make BUILD=release all`. |
| `debug` | `make BUILD=debug all`. |
| `test` | Build the test binary, copy to `build/tests/test-main`, run it. |
| `test-release` | `make BUILD=release test`. |
| `test-debug` | `make BUILD=debug test`. |
| `check-c23` | Print which C-standard flag was detected. |
| `vendor-deps` | Run `scripts/vendor-deps.sh`. |
| `clean` | Remove the entire `build/` directory. |

### Modes and flags

`BUILD ?= release`; any value other than `release` or `debug` is a hard error.
Mode presets:

| Variable | release | debug |
| --- | --- | --- |
| `OPTFLAGS` | `-O3 -DNDEBUG -march=native` | `-O0 -g` |
| `LTOFLAGS` | `-flto` | (empty) |

All of these are `?=` assignments and can be overridden on the command line,
as can `VENDOR_CFLAGS`, `PTHREADFLAGS` (default `-pthread`), `CFLAGS`,
`LDFLAGS`, and the tool variables `CC` and `AR`.

Project sources compile with `-Wall -Wextra -Wpedantic -Werror
-pedantic-errors` plus the detected C-standard flag (`-std=c23`, falling back
to `-std=c2x`, via `scripts/detect-c23-flag.sh`). Vendor sources are compiled
with `VENDOR_CFLAGS` only, deliberately without the warning set, so vendor
code is exempt from `-Werror`.

`vendor/sqlite/sqlite3.c` additionally compiles with `SQLITE_DEFINES`:

```
-DSQLITE_ENABLE_FTS5 -DSQLITE_THREADSAFE=2 -DSQLITE_OMIT_LOAD_EXTENSION
-DSQLITE_DQS=0 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_LIKE_DOESNT_MATCH_BLOBS
-DSQLITE_MAX_EXPR_DEPTH=0 -DSQLITE_OMIT_DEPRECATED -DSQLITE_USE_ALLOCA
```

`SQLITE_ENABLE_FTS5` is required by the schema. `SQLITE_THREADSAFE=2`
(multi-thread mode) requires that a connection is used by at most one thread
at a time; the application satisfies this by construction (per-operation
read connections, single-writer indexing). `SQLITE_DQS=0` disables
double-quoted string literals, so schema and query SQL always single-quotes
strings.

`PROJECT_LDFLAGS` filters `-D%` defines out of `OPTFLAGS` so `-DNDEBUG` never
reaches the linker. The final link is performed by `$(CC)`.

### Native Windows build

From `cmd.exe` or PowerShell, bootstrap with `scripts\vendor-deps.bat` and run
`build.bat release` or `build.bat debug`. The batch build invokes the Clang on
`PATH` in native MSVC-target mode and writes `build\code-lens.exe`; it does
not require make or a POSIX shell. `build.bat clean` removes generated output.
Unlike the Unix build, it compiles the pinned vendored zlib because Windows
does not provide a standard system `-lz`. Win32 compatibility code is compiled
without the project warning policy, while `src/code_lens.c` retains the full
pedantic/warnings-as-errors policy (with only SQLite's public-header
`__int64` diagnostic suppressed).

### Artifacts

| Artifact | Location |
| --- | --- |
| Project objects | `build/<mode>/obj/` |
| Mode binary | `build/<mode>/code-lens` |
| Canonical binary | `build/code-lens` (copy of the most recently built mode) |
| Test binary | `build/<mode>/tests/test-main`, copied to `build/tests/test-main` |
| SQLite object | `build/<mode>/obj/vendor/sqlite/sqlite3.o` |
| tree-sitter objects | `build/<mode>/obj/vendor/tree-sitter/lib.o`, `.../tree-sitter-clojure/parser.o`, `.../tree-sitter-java/parser.o`, and `.../tree-sitter-c/parser.o` |

### Build gotchas

- The canonical binaries are removed before being re-copied
  (`$(RM)` then `$(CP)` in the `all` and `test` targets). This is load
  bearing on macOS: overwriting a previously executed binary in place leaves
  a stale kernel code-signature cache for the vnode, and subsequent
  executions are killed with SIGKILL. Keep the remove-then-copy shape.
- Every project object depends on `include/code_lens.h`, so header edits
  rebuild all project code. This is cheap: it is a single translation unit.
- For CPU profiling with symbols, avoid LTO+debug combinations (very slow
  compiles). Use:

  ```sh
  make release OPTFLAGS='-O3 -g -DNDEBUG -march=native' LTOFLAGS=
  ```

  and run indexing with `CODE_LENS_INDEX_THREADS=1` so samplers have time to
  collect useful stacks.

### Profile-guided optimization (opt-in)

`make pgo` produces `build/code-lens-pgo`, a profile-guided binary measured
~9% faster than plain release on from-scratch indexing. It is deliberately not
the default release path: PGO needs an instrumented build, a training run, and
`llvm-profdata`, so clean builds stay portable and reproducible without any of
that. The pipeline is: build with `-fprofile-generate` under `BUILD=pgo-gen`,
run `scripts/pgo-train.sh` (index + query + context + sql + remove in an
isolated home), merge raw profiles with `llvm-profdata merge`, then rebuild
with `-fprofile-use` under `BUILD=pgo-use`. Train on a representative repo for
best results:

```sh
make pgo PGO_TRAIN_PATH=/path/to/big-clojure-repo
```

`PGO_TRAIN_PATH` defaults to the tiny `tests/fixtures/mcp-smoke` fixture, which
exercises every code path but yields a less representative profile than a real
repository. `LLVM_PROFDATA` overrides the profdata tool (defaults to the one on
`PATH`, falling back to `xcrun llvm-profdata`).

## 5. Vendored dependencies

Dependency source trees are local artifacts, not committed. `vendor/deps.lock`
holds the pins (format: `name url ref-or-sha256 license`; git dependencies
pin a commit, archive dependencies pin the SHA-256 of the archive):

| Dependency | Pin | License |
| --- | --- | --- |
| `sqlite-amalgamation` (SQLite 3.53.3) | archive SHA-256 `646421e12aac...500431` | Public domain |
| `tree-sitter` | `a858378ce7f4c124339cf67d8ad37341964fc0ee` | MIT |
| `tree-sitter-clojure` | `e43eff80d17cf34852dcd92ca5e6986d23a7040f` | MIT |
| `tree-sitter-java` | `94703d5a6bed02b98e438d7cad1136c01a60ba2c` (v0.23.5) | MIT |
| `tree-sitter-c` | `7fa1be1b694b6e763686793d97da01f36a0e5c12` (v0.24.1) | MIT |
| `libdeflate` | `c8c56a20f8f621e6a966b716b31f1dedab6a41e3` (v1.25) | MIT |
| `zlib` | `51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf` (v1.3.1) | zlib |

`scripts/vendor-deps.sh` clones each git dependency shallowly and checks out
the pinned ref (detached). The SQLite amalgamation is downloaded from the
pinned URL, verified against the pinned SHA-256, and extracted flattened into
`vendor/sqlite/` so `vendor/sqlite/sqlite3.c` and `sqlite3.h` sit directly in
that directory; a `.sha256` stamp file makes re-runs no-ops until the pin
changes. Run the script whenever `vendor/sqlite`, `vendor/tree-sitter`,
`vendor/tree-sitter-clojure`, `vendor/tree-sitter-java`, or
`vendor/tree-sitter-c` is missing.

To upgrade SQLite: pick the new amalgamation from sqlite.org/download.html
(the URL embeds the release year), verify the download against the published
hash, compute its SHA-256, and update the URL and hash in `vendor/deps.lock`.
No patches are applied to any vendored source.

## 6. Source architecture

`src/code_lens.c` is one translation unit organized into sections delimited
by `/* Section name */` comments. Keep new code in the appropriate section.

| Section | Responsibility |
| --- | --- |
| Preamble and shared helpers | `copy_bytes` (arena string duplication), `grow_array` (doubling arena arrays with overflow checks). |
| Arena allocator | Thread-local bump allocator; marks and resets. |
| Platform helpers | Home resolution, path utilities, mkdir/rmtree, file reads (heap for small files, mmap for large), supported-source file walking. |
| String builder | Arena-backed `StringBuilder` with `sb_append`/`sb_appendf`. |
| Dependency adapters | `code_lens_sqlite_version` and tree-sitter Clojure/Java/C metadata, used by `version --deps` and tests. |
| Database wrapper | SQLite open modes, prepared statements, parameter binding, the pipe-format result renderer, error reporting. |
| Clojure parser | Custom-reader/tree-sitter extraction of namespaces, aliases, definitions, references, and keywords. |
| Java parser | tree-sitter-java extraction of packages, imports, types/members, Javadocs, and resolved references. |
| C parser | tree-sitter-c extraction of declarations, aggregate types, macros, linkage, calls, and members. |
| Indexer | Schema DDL, prepared per-table INSERT writers, staging/publish paths, parallel parse workers. |
| Search | `list`, `query`, `context`, `sql` implementations over the per-repo indexes. |
| MCP server | stdio JSON-RPC loop, hand-rolled JSON scanning, tool dispatch. |
| CLI | Usage text, argument helpers, command dispatch. |
| Program entry point | `main`, guarded by `#ifndef CODE_LENS_NO_MAIN`. |

`include/code_lens.h` declares the public API: version constants, arena
functions, the parser data model (`CodeLensSymbol`, `CodeLensReference`,
`CodeLensNamespaceAlias`, `CodeLensSourceFile` and its language aliases), database and index entry
points, filesystem helpers, the search functions, and the CLI/MCP mains. It
includes `<sqlite3.h>`; `CodeLensDb` wraps a `sqlite3 *` handle plus the
read-deadline used by the progress handler.

The tests compile `src/code_lens.c` a second time with `-DCODE_LENS_NO_MAIN`
so `tests/test_main.c` can provide its own `main` while linking the full
application object (see [Testing](#16-testing)).

## 7. Memory model: the arena allocator

All project allocations flow through a thread-local arena.

Design:

- Each thread owns a chain of `malloc`'d blocks
  (`_Thread_local thread_first_block` / `thread_current_block`). The base
  block size is 8 MiB (`CODE_LENS_ARENA_BLOCK_SIZE`); a new block doubles
  capacity until the request fits.
- Every allocation is preceded by a `CodeLensAllocationHeader` recording its
  size, aligned to `_Alignof(max_align_t)`. The header is what lets
  `code_lens_resize` know how much to copy on growth, and lets it shrink in
  place.
- `arena_alloc_raw` bump-allocates from the current block, advances to the
  next (resetting its `used` to zero - blocks are recycled after resets), or
  links a fresh block. Blocks are never freed.
- Lifetimes are managed exclusively with marks: `code_lens_arena_mark`
  captures (current block, used); `code_lens_arena_reset` restores it.
  Usage is stack-like. The MCP loop marks and resets around each message,
  keeping the long-running server's memory flat.
- `code_lens_resize(ptr, 0)` returns null. On growth, the old region is
  abandoned in place, never freed.
- `sb_free` on the string builder only clears the struct; the buffer is arena
  memory and is reclaimed by the next reset.

Rules:

- Never `free` a pointer returned by `code_lens_alloc`,
  `code_lens_alloc_zeroed`, `code_lens_resize`, `copy_bytes`, or the string
  builder. Never add no-op "cleanup" helpers for arena pointers; if removing
  cleanup noise leaves an empty helper, delete the helper.
- Real external cleanup is still mandatory and must be preserved:
  `ts_parser_delete`, `ts_tree_delete`, `ts_tree_cursor_delete`,
  `sqlite3_finalize` for every prepared statement, `sqlite3_close`,
  `sqlite3_free` for error strings from `sqlite3_exec`, `munmap`,
  `closedir`, `close`, and pthread mutex/condvar destruction.
- Worker threads leak their arena blocks at thread exit by design; this is
  accepted for the one-shot `index` operation.
- Arena memory is what makes `SQLITE_STATIC` bindings safe in the indexer:
  the bound strings outlive the statement step because nothing resets the
  arena during an index run. Stack-local buffers must bind with
  `SQLITE_TRANSIENT` instead (see `bind_text_copy_len`).

## 8. Platform helpers and file walking

- `code_lens_default_home`: `$CODE_LENS_HOME` if set and non-empty, else
  `$HOME/.code-lens`. Each repo's database lives at
  `<home>/repos/<repo-dir>/index.sqlite` (`repo_db_path`), where
  `<repo-dir>` is the sanitized basename of the canonical repo path plus an
  FNV-1a 64-bit hash of the full canonical path (`repo_dir_component`), so
  distinct repo paths never share a directory. Explicit CLI commands and
  public APIs identify a repository by its canonical path alone
  (`canonical_repo_path`: `~/` expansion + `realpath`), retain support for
  plain directories, and use `resolve_repo_id` to walk up from an input path
  probing for an existing index, so a path inside an indexed repository
  resolves to that repository. MCP uses `mcp_worktree_root` to accept a
  worktree root or any file/directory inside it, walking upward to validate the
  nearest `.git` directory or gitfile without invoking Git. An omitted `repo`
  resolves from `.` (the MCP server working directory); standalone plain directories and
  bare repositories are rejected.
- `repo_write_lock_acquire`: opens the canonical repo's persistent lock file
  below `<home>/repos/.locks/` and waits for an exclusive whole-file `fcntl`
  lock. `code_lens_index_repository` holds it across the stale recheck,
  staging build, publish, and cleanup; `code_lens_remove_repo` takes the same
  lock. Thus competing MCP refreshes serialize, and a waiter sees the first
  writer's publication when its incremental path rechecks staleness. The files
  are deliberately never unlinked: process exit releases advisory ownership,
  while unlinking could let later callers lock a new inode alongside waiters
  on the old one.
- `code_lens_map_file`: files up to 256KB are `read()` into a heap buffer
  (eight parse workers unmapping ~2,000 short-lived mappings caused constant
  TLB-shootdown IPIs); larger files use `mmap(PROT_READ, MAP_PRIVATE)` with
  `MADV_SEQUENTIAL`. Empty files short-circuit to a static empty string with
  no mapping. `code_lens_mapped_file_free` frees or unmaps accordingly.
- Git blob source (`GitBlobSource`/`GitBlobReader`): when the repo root has
  a `.git` directory, indexing parses `.git/index` (v2/v3, SHA-1) once,
  mmaps every `objects/pack/*.{idx,pack}` (idx v2), and serves each clean
  tracked file from the object store — zlib-inflating packed blobs and
  resolving ofs/ref-delta chains, with per-worker memoization of delta
  bases — instead of opening the working file. A file qualifies only if
  one `lstat` matches the index entry exactly (regular file, size, mtime
  seconds, mtime nanoseconds when recorded) and the entry is not racily
  clean (entry mtime at or after the index file's own mtime): the same
  contract `git status` trusts. Everything else — untracked or modified
  files, merge stages, assume-valid/skip-worktree bits, symlinks, loose
  objects missing, any parse or inflate error, sha256 repos, unsupported
  index/pack versions — falls back to reading the working file per file,
  or leaves the source inactive entirely, so indexed content is always
  byte-identical to a plain filesystem build. Cuts summed worker read CPU
  ~15x on the warm benchmark fixture (~4 syscalls per file down to one
  `lstat`) and roughly 3.6x cold. `CODE_LENS_GIT_BLOBS=0` disables it;
  `CODE_LENS_PROFILE=1` prints `git_blob_reads`/`git_file_reads`
  (exported as `code_lens_git_blob_read_count`/
  `code_lens_git_file_read_count`). Requires the system zlib
  (`PROJECT_LDLIBS = -lz`).
- The recursive walker (`walk_path`) accepts an extension predicate. The
  indexing and staleness paths use `has_supported_extension` for `.clj`,
  `.cljc`, `.cljs`, `.bb`, `.java`, `.c`, and `.h`; the public Clojure compatibility
  walker remains Clojure-only.
  - Skipped directories (`should_skip_dir`): `.git`, `node_modules`, `vendor`,
    `target`, `build`, `.shadow-cljs`, `.cpcache`, `.idea`, `.vscode`,
    `.lsp`. `.clj-kondo` is deliberately walked: real repos keep `.clj`
    config/export files inside it.
  - A `d_type` fast path skips non-directories/non-source entries without
    calling `stat`.
  - Paths are built in a single reusable `PathBuffer` with mark/restore
    around each recursion level.

Path lifetime rule: `CodeLensFileCallback` receives a path that is valid
only for the duration of the callback (`include/code_lens.h`) - the walker
mutates the shared buffer immediately afterwards. Any callback that keeps a
path must copy it, as the internal collector does (`path_list_append` copies
via `copy_bytes`).

## 9. Language parsers

### Clojure

Clojure parsing has two backends behind one entry point:

- **Custom reader (default).** A hand-written Clojure reader
  (`cp_parse_clojure` and the `cp_*` helpers in `src/code_lens.c`) lexes and
  parses directly into a flat `CpNode` pool and walks it with the same
  extraction semantics as the tree-sitter path. It exists purely for speed:
  on the benchmark fixture it cuts parse+extract CPU ~13x versus tree-sitter.
  It is written against `vendor/tree-sitter-clojure/grammar.js` as the
  behavioral spec — token boundaries, whitespace set, number/char/keyword
  tie-breaks, metadata attachment, and row/column accounting all match the
  grammar, and the differential test in `tests/test_clojure_parser.c` plus
  byte-identical fixture-index hashes are the regression oracle for that.
- **tree-sitter fallback.** Any construct the reader does not model with
  certainty (for example `#=` eval literals, a stray `#_` at top level with
  nothing following, unbalanced delimiters, or pathological depth) makes the
  reader decline the whole file — it returns nonzero without touching the
  output struct, the process-wide atomic counter reported by
  `code_lens_clojure_reader_fallbacks()` increments, and the file is parsed
  by tree-sitter instead. Correctness never depends on the reader accepting
  a file. `CODE_LENS_PROFILE=1` prints the counter (`reader_fallbacks`).
- Setting `CODE_LENS_PARSER=treesitter` in the environment forces the
  tree-sitter backend for every file (the escape hatch; also how the tests
  run both backends differentially).

The parser (either backend) extracts, per file:

- the namespace (from the `ns` form),
- require aliases (`[:require [foo.bar :as fb]]` pairs; `:as-alias` counts as
  `:as`; alias defaults to the empty string when neither is present).
  Requires under reader conditionals are extracted from every platform
  branch — clause level `#?(:clj (:require …))`, spec level
  `#?(:clj [a :as b] :cljs [c :as d])`, and splicing
  `#?@(:clj [[a :as b] …])` (each splicing branch is a vector of specs),
- referred symbols: `:refer [a b]` records one `(namespace, symbol)` pair per
  symbol; `:refer :all` and `(:use …)` specs record the `:all` sentinel
  (`:only` narrowing and `:rename` are not tracked). Reader conditionals are
  unwrapped the same way as for aliases,
- definitions (name, kind, docstring, full source text of the form, start and
  end lines),
- references: every symbol literal in the file, with 1-based line and column.

Implementation details that matter:

- Grammar node-type IDs (`sym_lit`, `list_lit`, `vec_lit`, `str_lit`,
  `comment`, `dis_expr`, `meta_lit`, `old_meta_lit`) are resolved once via
  `pthread_once` and compared as `TSSymbol` values on the hot path, not as
  strings. Comments, `#_` discard expressions, and metadata nodes are
  filtered out of structural traversal.
- Text handling is zero-copy: `SourceSlice` views point into the loaded
  source buffer. All literal comparisons use length-checked
  `source_slice_equals` - `slice.len == text_len` plus `memcmp`. Do not
  replace these with prefix `strncmp`; `defnx` must not match `defn`. This is
  a stated invariant; the benchmark fixture's exact counts are the regression
  oracle for it (see
  [Benchmarking](#17-benchmarking-and-performance-workflow)).
- Symbol names are extracted with grammar field access, not raw node spans.
  The grammar nests metadata inside the annotated `sym_lit` node, so the raw
  span of `^:private foo` includes the metadata. `symbol_value_slice` slices
  from the start of the `namespace` field child (or the `name` field child
  when unqualified) to the end of the node, which drops any `meta`/`old_meta`
  prefix; every extracted name, reference text, namespace, and require alias
  goes through it (via `symbol_text`).
- Definition detection compares the base name - the text of the grammar's
  `name` field child (`symbol_base_slice`), which is the part after the
  namespace delimiter - so a namespaced head like `foo/defn` and a
  metadata-annotated head are both still recognized. The kind map
  (`is_definition_form_base`):

  | Head | Kind |
  | --- | --- |
  | `defn`, `defn-` | `function` |
  | `def` | `var` |
  | `defmacro` | `macro` |
  | `defmulti` | `multimethod` |
  | `defmethod` | `method` |
  | `defprotocol` | `protocol` |
  | `defrecord` | `record` |
  | `deftype` | `type` |
  | `deftest` | `test` |

- The docstring is the first string literal after the definition name and
  before any vector or list (a heuristic; quotes are stripped).
- `:require` handling (`extract_ns_form` and `extract_require_aliases`)
  scans require vectors for the required namespace symbol and an optional
  `:as` alias.
- Sources larger than `UINT32_MAX` bytes are rejected before parsing
  (tree-sitter length limit).
- Entry points: `code_lens_clojure_parser_new` / `_delete`,
  `code_lens_parse_clojure_source_with_parser` (reusable parser, used by the
  indexer), and `code_lens_parse_clojure_source` (one-shot convenience).
  Trees and cursors are deleted after each parse; the parser object is reused
  across files and deleted with `ts_parser_delete`.

### Java

Java parsing always uses the pinned tree-sitter-java grammar. A reusable
`CodeLensJavaParser` owns one `TSParser`; `CodeLensSourceParser` owns one
parser for each supported language per indexing worker and dispatches by extension.
The extractor runs ordered passes over each Java syntax tree:

1. Read the package and imports. Normal imports become `Alias` mappings from
   the simple name to the fully qualified type; static imports become
   `Referred` rows, using `:all` for a wildcard.
2. Collect all top-level, nested, and local type declarations so forward type
   references can resolve.
3. Extract definitions and declared receiver types. Definitions include
   classes, interfaces, enums, annotations, records, constructors, methods,
   fields, record components, enum constants, annotation elements, and modules. JUnit-style
   annotated methods are kind `test`; immediately preceding `/** ... */`
   comments become cleaned docs.
4. Extract type references, annotations, field accesses, method references,
   and method invocations. Receiver targets are resolved from normal/static
   imports, the enclosing/super type, and declared field, parameter, local,
   enhanced-for, and resource types.

Java type and member symbols use the fully qualified enclosing type as their
`Symbol.namespace` (for example both class `Service` and its method `execute`
use `com.example.Service`). `File.namespace` remains the Java package. This
lets the existing context query match a reference's resolved target directly
to candidate definitions. Resolution is deliberately syntax-driven: code-lens
does not run javac or perform compiler attribution, so dynamic/chained receiver types that
require compiler attribution may retain an empty target and are not claimed as
resolved call sites.

Entry points are `code_lens_java_parser_new` / `_delete`,
`code_lens_parse_java_source_with_parser`, and the one-shot
`code_lens_parse_java_source`. `tests/test_java_support.c` covers extraction,
imports, target resolution, walking, indexing, kind filters, SQL, and dotted
Java context lookup.

### Dependency source sets

`maven_dependencies_prepare` detects a root build file in priority order:
`pom.xml`, `project.clj`, then `deps.edn`. Maven projects snapshot every reactor
POM, recursively discovered local parent POM, `mvnw`/`mvnw.cmd`, and every file
below `.mvn`; Leiningen/tools.deps snapshot their root build file. The legacy
`MavenProject.rootPom` column stores that selected build path. A repository with
no recognized build stores status `none`, making an empty dependency scope
explainable and reusable until build-file detection changes. When an input
snapshot still matches `MavenInput`, prior `DependencyArtifact` rows and
checksum-keyed materialized roots are reused. The staleness engine checks the
same inputs and dependency files. A stored `failed` status (or `disabled` after
resolution is enabled) is intentionally non-reusable, so transient first-index
failures retry without requiring an input edit.

On a Maven cache miss, `run_process_with_timeout` invokes the wrapper or Maven
in batch/no-transfer-progress mode and runs the pinned Maven Dependency Plugin's
`copy-dependencies` goal with the `sources` classifier, test classpath scope,
reactor exclusion, and repository-layout output. Maven artifacts form a
repository-wide reactor union with scope `test-classpath`. Leiningen runs `lein
classpath`; tools.deps runs `clojure -Spath`; their platform-separated output is
the resolved artifact set and uses scope `classpath`. Classpath entries prefer
a sibling `-sources.jar` and otherwise materialize the main JAR. All modes set
`DependencyFile.modulePath` to the repository root and `direct = -1`. The
120-second default is overridden by `CODE_LENS_MAVEN_TIMEOUT_MS`;
`CODE_LENS_MAVEN=0` is the global kill switch. Command overrides are
`CODE_LENS_MAVEN_COMMAND`, `CODE_LENS_LEIN_COMMAND`, and
`CODE_LENS_CLOJURE_COMMAND`. POSIX uses `fork`/`exec` plus timed `waitpid`;
Windows uses the CRT spawn API plus `WaitForSingleObject`.

Maven repository layouts supply GAV metadata without filename guessing. The
built-in ZIP reader validates central/local bounds, rejects encrypted, ZIP64,
unsafe, and unsupported entries, verifies CRC-32, and extracts stored/deflated
`.clj`, `.cljc`, `.cljs`, `.bb`, and `.java` entries. Extracted roots live at
`dependencies/sources/<group>/<artifact>/<version>/<FNV-1a checksum>` below
`CODE_LENS_HOME`; persistent per-checksum advisory locks make publication safe
across repositories and processes. Marker validation and artifact collection
use the same supported-source predicate, so mixed-language caches remain
reusable.

Dependency Clojure and Java files pass through the normal parser/emitter and
hot index tables. `DependencyFile` links those rows to `DependencyArtifact`
without adding origin columns to `File`, `Symbol`, or `RefData`, which keeps the
raw emitter's high-volume layouts unchanged. Query defaults to rows with no
`DependencyFile`; `scope=dependencies|all` and the GAV glob opt in. Context
selects workspace candidates first, falls back to dependency candidates, and
orders workspace reference sites before dependency-internal references.
Dependency metadata is written with ordinary prepared statements inside the
same staging transaction. A build-tool refresh failure with an existing index
aborts before staging and leaves that generation readable.

### C

C parsing uses the pinned tree-sitter-c grammar. A reusable `CodeLensCParser`
owns one `TSParser`. The extractor first collects typedef aliases and canonical
aggregate identities, then extracts definitions and declaration scopes, and
finally emits references with syntax-level target resolution.

Definitions include function definitions and prototypes, global variables,
typedefs, named structs/unions/enums, fields, enumerators, and object-like or
function-like macros. Immediately preceding `/** ... */` comments become docs.
Function-pointer declarators remain `variable`/`typedef` symbols rather than
being misclassified as functions.

C has no package namespace, so the representation uses linkage and aggregate
scope:

- externally linked functions/variables, types, macros, and enumerators use an
  empty `Symbol.namespace`;
- file-local `static` functions and globals use the source file path;
- fields use the canonical aggregate name (following typedef aliases);
- every C `File.namespace` is empty.

This fits the existing context SQL: empty-target calls resolve to global
symbols, explicit file-path targets resolve static calls/variables only within
the defining file, and `.`/`->` member references target field definitions in
their aggregate namespace. Declared parameter/local/global types, typedefs,
and nested field types drive member resolution. Calls through local function
pointers and expression types requiring compiler attribution are deliberately
left unresolved rather than guessed.

Entry points are `code_lens_c_parser_new` / `_delete`,
`code_lens_parse_c_source_with_parser`, and `code_lens_parse_c_source`.
`tests/test_c_support.c` covers macros, linkage, function pointers, typedefs,
aggregates, calls, member accesses, walking, staleness, incremental indexing,
kind filters, and context.

## 10. Database layer and schema

### Connection handling

- `code_lens_db_open_read` opens `SQLITE_OPEN_READONLY`, sets a 30-second
  busy timeout, verifies the index format stamp (`PRAGMA user_version` must
  equal `CODE_LENS_INDEX_FORMAT_VERSION`; a mismatch fails the open with a
  "re-index required" message), and installs a `sqlite3_progress_handler`
  that aborts any statement once a 30-second connection deadline passes.
  SQLite has no per-query timeout API; the progress handler is what keeps
  the "capped read-only query" promise.
- `code_lens_db_open_build` opens `SQLITE_OPEN_READWRITE | CREATE` against a
  staging file and sets `PRAGMA journal_mode=OFF`, `synchronous=OFF`,
  `temp_store=MEMORY`. These pragmas are safe only because a failed or
  interrupted build leaves nothing but a stale staging file that is deleted
  on cleanup; the published index is only ever replaced by an atomic rename.
  Never reuse these pragmas on a database opened in place.
- `code_lens_db_close` closes the handle; all prepared statements must be
  finalized first or the close reports an error.

### Error reporting

`report_sqlite_error` prints `code-lens: <prefix>: <sqlite3_errmsg()>` to
stderr. Query failures surface as `code-lens: query failed: <message>`,
either from `sqlite3_exec`'s error string (which must be released with
`sqlite3_free`) or from `sqlite3_errmsg` after a failed prepare/step.

### Result rendering

`render_stmt_result` produces the pipe format shared by every read command:
one header line of column names joined with `|`, then one line per row with
values joined with `|`, SQL NULL rendered as an empty string, and every line
newline-terminated (including the last). Multi-line column values (docstrings
and definition sources) are emitted raw. The renderer also reports the row
count, which callers use to distinguish empty results from failures; nothing
parses rendered text back.

App queries use explicit column aliases (for example `s.name AS "s.name"`)
so headers stay stable regardless of SQLite's default column naming.

### Schema

The schema is created by one DDL block (`index_schema_sql`), stamped with
`PRAGMA user_version = 13` (`CODE_LENS_INDEX_FORMAT_VERSION`). Twelve row
tables plus one FTS5 index table and two compatibility views:

| Table | Primary key | Columns |
| --- | --- | --- |
| `Repo` | `path` | `path`, `indexedAt` (TEXT); `fileCount`, `symbolCount`, `referenceCount`, `keywordCount`, `aliasCount` (INTEGER) |
| `File` | `id` | `id`, `repo`, `path`, `namespace` (TEXT); stat snapshot and per-file Ref/Keyword/Symbol range columns (INTEGER) |
| `Symbol` | `id` | `id`, `repo`, `name`, `kind`, `namespace`, `filePath` (TEXT); `startLine`, `endLine` (INTEGER); `content`, `doc` (TEXT) |
| `Term` | `id` | `id` (INTEGER); `text` (TEXT, unique) |
| `RefData` | `id` | `id`, `fileId`, `symbolTerm`, `lineNumber`, `columnNumber`, `startByte`, `endByte`, `symbolBaseTerm`, `targetNamespaceTerm` (all INTEGER) |
| `KeywordData` | `id` | `id`, `fileId`, `keywordTerm`, `lineNumber`, `columnNumber`, `keywordBaseTerm`, `qualifierTerm`, `targetNamespaceTerm` (all INTEGER) |
| `Alias` | `id` | `id`, `repo`, `filePath`, `namespace`, `alias` (TEXT) |
| `Referred` | `id` | `id`, `repo`, `filePath`, `namespace`, `symbol` (TEXT) |
| `MavenProject` | `repo` | `repo`, `rootPom`, `status`, `resolvedAt`, `message` (TEXT) |
| `MavenInput` | `id` | `id`, `repo`, `path` (TEXT); `size`, `mtimeSec`, `mtimeNsec` (INTEGER) |
| `DependencyArtifact` | `id` | `id`, `repo`, `coordinate`, `groupId`, `artifactId`, `version`, `scope`, `sourceJar`, `sourceRoot`, `checksum` (TEXT); `direct` (INTEGER, `-1` when Maven did not expose directness) |
| `DependencyFile` | `id` | `id`, `repo`, `filePath`, `artifactId`, `sourcePath`, `modulePath` (TEXT) |

Maven rows are low-volume metadata. `DependencyArtifact` records the resolved
GAV, copied source JAR, checksum-keyed extraction root, and classpath scope.
`DependencyFile.artifactId` links each external `File.path` to that artifact;
absence of a mapping is the definition of workspace scope. `MavenInput` is the
stat snapshot used to avoid invoking Maven on ordinary source-only refreshes.
These tables and their lookup indexes are populated through SQLite even when
the hot source tables use the raw page emitter.

`RefData` and `KeywordData` are the two high-volume tables (hundreds of
thousands of rows on large repos), so they are kept deliberately narrow and
fully dictionary-encoded: `fileId` holds the `File` rowid of the source file,
and every text column holds a `Term` rowid instead of the string itself.
`Term` is the interned string dictionary (typically ~40k distinct strings for
~450k references — an 11–27x repetition factor), which shrinks the database
and the write volume substantially.

Two views, `Ref` and `Keyword`, reconstruct the old text-shaped rows via
`Term` joins with the exact historical column names (`symbol`, `symbolBase`,
`targetNamespace`, `keyword`, `keywordBase`, `qualifier`), so app queries,
tests, and agent-issued `sql` queries are unchanged. Both views also expose
`filePath` through a `LEFT JOIN File ON File.rowid = fileId`: agents joining
by hand reached for `File.id` (a TEXT key unrelated to `fileId`) and silently
got zero rows. The LEFT JOIN on the rowid lets SQLite's omit-noop-join
optimization drop the join for queries that never touch `filePath`. SQLite
flattens these simple join views; the refs lookup plan probes `idx_term_text`
then `idx_ref_symbol_base`. Keyword `LIKE` filters run against the
vocabulary-sized `Term` table (`k.keywordBaseTerm IN (SELECT id FROM Term
WHERE text LIKE …)`) rather than per-row text. App queries alias joined
columns (`f.path AS "r.filePath"`, `f.namespace AS "k.sourceNamespace"`) so
rendered headers are unchanged.

`Ref.symbol` keeps the reference text verbatim (for display and debugging).
For Clojure, `Ref.symbolBase` is the part after the last `/` and
`Ref.targetNamespace` is the qualifier resolved through the file's require
aliases: an alias hit stores the aliased namespace, a miss stores the qualifier
verbatim, and unqualified references store the empty string. For Java, the
extractor fills `symbolBase` with the final referenced type/member and
`targetNamespace` with the resolved fully qualified type when syntax-level
resolution succeeds. For C, `symbolBase` is the final identifier and the target
is a file path for known `static` symbols, an aggregate name for typed member
accesses, or empty for global/unresolved names. `symbol` can retain a qualified
source spelling such as `service.execute` or `point->x`. `Ref.startByte` and `Ref.endByte` are zero-based byte
offsets for the base token highlighted in source. `lineNumber` and `columnNumber` remain one-based
display/navigation fields; the column value follows tree-sitter byte-column
semantics rather than Unicode grapheme semantics.

`Keyword.keyword` keeps the literal keyword text including `:` or `::`.
`Keyword.keywordBase` is the name part after any `/` with the marker stripped.
`Keyword.qualifier` is the typed qualifier before `/`, if present.
`Keyword.targetNamespace` resolves auto-resolved keywords: `::local` stores the
current file namespace, `::alias/name` stores the require-alias target when
known, and ordinary `:foo/bar` stores `foo` verbatim.

`File.size`, `File.mtimeSec`, and `File.mtimeNsec` are a best-effort source
snapshot recorded at index time. `list`, `query`, and `context` use it to
warn when indexed files are missing/changed and when new supported Clojure,
Java, or C source files exist outside the index.

Plain indexes: `idx_symbol_name` on `Symbol(name)`, `idx_term_text` (unique)
on `Term(text)`, `idx_ref_symbol_base` on `RefData(symbolBaseTerm)` (the
`context` lookups), and `idx_keyword_base` on `KeywordData(keywordBaseTerm)`.

`SymbolFts` is an external-content FTS5 table over `Symbol` (columns `name`,
`namespace`, `filePath`, `doc`, `content`), so no row text is stored twice.
Its tokenizer is `unicode61` with extra separators `- . / _`, which splits
Clojure identifiers into word parts (`refund-date-type-enabled?` indexes as
`refund`, `date`, `type`, `enabled`). Trailing-`*` prefix queries are
answered from the main term index; the table deliberately carries no
`prefix=` indexes because they cost roughly 10x the FTS build time while
saving only a few milliseconds on worst-case short-prefix queries at this
corpus size. The table is populated once per build by the **FTS sidecar**:
a side thread receives a copy of every `Symbol` row as it is emitted (in
rowid order) and builds an identically-declared `SymbolFts` in a sidecar
database (`staging-XXXXXX.fts`) with a raised FTS5 `hashsize` (8MiB), so
the whole index accumulates in memory and the sidecar's COMMIT writes one
segment instead of flushing several level-0 segments and auto-merging
them. The finished shadow tables
(`SymbolFts_data`/`_idx`/`_docsize`/`_config`)
are copied into the staging database inside the load transaction. This
hides the FTS build behind the emit and index phases. The logical FTS
content is identical to an in-place
`INSERT INTO SymbolFts(SymbolFts) VALUES('rebuild')` (same rows in the
same order; a rebuild scans `Symbol` in rowid order), though segment bytes
may differ — nothing depends on shadow-table bytes. Any sidecar failure
(or `CODE_LENS_FTS_SIDECAR=0`) falls back to the in-place rebuild under
the classic emitter; the raw emitter requires the sidecar (raw rows are
not SQL-visible until the post-COMMIT graft, so an in-place rebuild would
see an empty `Symbol`) and instead fails over to a classic-emitter retry.
There are no triggers because the index is write-once.

The `repo` columns on `Repo`, `File`, `Symbol`, `Alias`, and `Referred` are
retained and every app query still filters `WHERE repo = ?` (via the `File`
join for `Ref`/`Keyword` rows), even though each database holds exactly one
repo.

`Referred` records what Clojure `:refer` / `:refer :all` / `(:use …)` and
Java static imports make visible unqualified in each file — one row per
referred symbol, with the sentinel symbol `:all` for whole-namespace/type
referral. `context` reads it to decide which unqualified references can
actually resolve to the looked-up symbol.

ID composition (see `insert_*_row`; `{repo}` is the canonical repo path):

| Table | ID format |
| --- | --- |
| `File` | `{repo}\|file\|{path}\|` (trailing separator is intentional) |
| `Symbol` | `{repo}\|symbol\|{path}\|{startLine}\|{symbolIndex}\|{name}` (`symbolIndex` is the per-file symbol ordinal, so same-name same-line definitions in reader-conditional branches stay unique) |
| `Ref` | decimal global ordinal (running reference count across the whole index run) |
| `Keyword` | decimal global ordinal (running keyword count across the whole index run) |
| `Alias` | `{repo}\|alias\|{path}\|{namespace}\|alias\|{alias}\|{aliasIndex}` |
| `Referred` | `{repo}\|referred\|{path}\|{namespace}\|referred\|{symbol}\|{referredIndex}` |

Because `Ref` IDs are ordinals assigned in emission order, deterministic
emission order matters (see [parallelism](#12-indexing-parallelism)).

### Parameter binding

Application queries never interpolate user input into SQL text; every
user-supplied value binds as a `?N` parameter (`bind_text` maps NULL to the
empty string so the schema stores no SQL NULLs). The only SQL that runs
verbatim is the `sql` command's user query, on a read-only connection with
a reads-only authorizer and a 100-row output cap.
Binding lifetimes: `SQLITE_STATIC` only for arena-backed memory that
outlives the step; stack buffers bind through `bind_text_copy_len`
(`SQLITE_TRANSIENT`).

## 11. The indexing pipeline

`code_lens_index_repository` builds each repo's database in a staging
SQLite file and publishes it atomically. The default raw emitter encodes
rows directly into b-tree pages and grafts them after a tiny COMMIT; the
classic emitter streams rows through prepared INSERT statements inside one
transaction. Before either path starts, it takes the repo's blocking writer
lock; the lock remains held through publication and stale-artifact cleanup.

```
walk repo for .clj/.cljc/.cljs/.bb files
        |
        v
parse workers (tree-sitter, per-thread parser + arena)
        |
        v  (ordered, single-threaded emit)
raw b-tree leaf pages (File/Symbol/Ref/Keyword/Alias/Referred/Term rows)
in memory; Symbol rows mirrored to the FTS sidecar thread
        |
        v
Repo row -> adopt sidecar FTS -> COMMIT (tiny: schema + Repo + FTS)
        |
        v
graft raw table + index b-trees into the staging file (post-COMMIT)
        |
        v
atomic rename() -> <home>/repos/<repo-dir>/index.sqlite
        |
        v
delete stale staging files and legacy pre-SQLite artifacts
```

Step by step (the default **raw emitter** path; `CODE_LENS_EMITTER=classic`
restores the historical all-SQL path, and any raw-path failure abandons the
staging file and retries the whole build with the classic emitter, so the
raw path can never affect correctness — including constraint violations:
`raw_payloads_from_text_keys` rejects duplicate keys when building UNIQUE
index payloads (the File/Symbol/Alias/Referred autoindexes and
`idx_term_text`), so
a would-be corrupt unique index fails the raw attempt instead of being
published, and the classic retry then reports the underlying constraint
error. Symbol ids embed a per-file ordinal —
`repo|symbol|path|line|ordinal|name` — precisely so that duplicate keys
cannot arise from legal Clojure such as
`#?(:clj (def x 1) :cljs (def x 2))` defining the same name twice on one
line):

1. Resolve the worker count (`configured_index_thread_count`) and optionally
   enable phase profiling (`CODE_LENS_PROFILE`).
2. `init_index_paths` creates the repo directory and a unique staging file
   `mkstemp("<repo-dir>/staging-XXXXXX")`.
3. `code_lens_db_open_build` opens the staging file with build pragmas;
   `create_index_schema` runs the DDL — tables, views, secondary indexes,
   and the FTS virtual table are all created empty up front, so SQLite
   itself writes every `sqlite_master` row and allocates every root page;
   `BEGIN` opens the load transaction.
4. Files are parsed by the worker pool (see next section) and emitted in
   discovery order by the coordinator. Per parsed file: one `File` row,
   then its `Symbol`, `Ref`, `Keyword`, `Alias`, and `Referred` rows
   (`emit_parsed_file`). Each row is encoded straight into in-memory raw
   table b-tree leaf pages (rowids are dense emission ordinals, so leaves
   fill append-only); records that fit a leaf cell are encoded in place,
   larger ones spill to overflow-page chains. Text columns store integer
   ids into the shared `Term` table (interned via a wyhash-style hash
   table). Each `Symbol` row is also mirrored to the FTS sidecar thread,
   which tokenizes concurrently into a sidecar database with a raised FTS5
   `hashsize` so its final COMMIT writes a single segment.
5. Once the walk finishes, three prep threads start sorting and encoding
   the eight secondary-index payloads (four table autoindexes plus
   `idx_symbol_name`, `idx_ref_symbol_base`, `idx_keyword_base`,
   `idx_term_text`) while the main thread finishes the SQL work below.
6. A single `Repo` row is written with the final counts and an ISO-8601 UTC
   `indexedAt` timestamp; the sidecar's finished FTS shadow tables are
   adopted into the staging database, and the transaction commits. The
   COMMIT is tiny: schema, `Repo`, and FTS shadow rows are the only pages
   SQLite ever wrote. (Raw mode requires the sidecar: with
   `CODE_LENS_FTS_SIDECAR=0` the attempt fails over to the classic
   emitter, whose in-place FTS rebuild can read `Symbol` via SQL.)
7. Statements are finalized and the connection closes. The graft step then
   appends the finished table b-trees to the staging file (three threads,
   byte-identical output to a sequential write), overwrites each
   schema-allocated root page in place with the real root, joins the prep
   threads, and writes the secondary-index b-trees the same way. No
   `sqlite_master` surgery is needed because the DDL text is byte-identical
   to the classic path.
8. `publish_index` atomically `rename()`s the staging file onto
   `index.sqlite`.
9. `cleanup_stale_index_artifacts` removes leftovers from interrupted or
   pre-SQLite builds in that repo directory: `staging-*` files, legacy
   `index.lbug` databases, and legacy `icebug-*` staging directories. On
   failure, the error path deletes the staging file instead.

With `CODE_LENS_EMITTER=classic`, rows go through prepared INSERTs instead
(the two high-volume tables `Ref` and `Keyword` through 16-row multi-row
`VALUES` statements, remainder rows per file through the single-row
statements, all inside the one transaction), secondary indexes are built by
`CREATE INDEX` before COMMIT (or, when `CODE_LENS_RAW_INDEXES` is not `0`,
as raw b-tree pages grafted after COMMIT with a plain `CREATE INDEX`
fallback), and a sidecar failure falls back to an in-place FTS rebuild.

## 12. Indexing parallelism

Indexing parallelizes the read/parse/extract phase; emission stays
single-threaded and ordered.

- Worker count: default `min(online CPUs, 8)`
  (`DEFAULT_MAX_INDEX_THREADS`). `CODE_LENS_INDEX_THREADS` overrides it:
  `0` or `1` forces the fully sequential path (walk, parse, and emit inline
  per file); values above 64 (`MAX_INDEX_THREADS`) are clamped with a
  warning; invalid values warn and fall back to the default.
- Pool design (`index_source_files_parallel`):
  - A dedicated walker thread streams discovered paths into a mutex-guarded
    chunked entry list while parse workers consume them, so directory
    traversal overlaps parsing instead of serializing in front of it. Each
    path is copied onto the walker's arena (see the callback path lifetime
    rule); entry chunks never move once published, so a claimed entry can be
    used without the lock.
  - Each worker owns a `CodeLensSourceParser` containing reusable Clojure,
    Java, and C parsers and, implicitly, its own thread-local arena; allocation
    requires no locking.
  - Workers claim the next unclaimed entry under the mutex (cond-waiting
    until the walker publishes more paths or finishes), parse (read or map
    the file, parse, release), publish the result into the entry, and flag
    it ready under the
    mutex, broadcasting a condvar. Any failure aborts the pool, including
    the walk.
  - The main thread is the only consumer: it waits for the entry at
    `emit_index` to become ready and emits results strictly in discovery
    order.
- Ordered emission is not cosmetic: `Ref` IDs are global ordinals derived
  from the running reference count, so emission order determines IDs.
  Reordering emission changes the index content.
- The single-consumer emit loop is also what satisfies
  `SQLITE_THREADSAFE=2`: only the main thread touches the write connection.
- Per-worker profile counters are merged after `pthread_join`, so there is no
  contention during the run. The mutex and condvar are destroyed afterwards.

## 13. Search and query semantics

All read operations open the repo's database read-only and format results
with the project's own pipe renderer (see
[result rendering](#10-database-layer-and-schema)).

Explicit CLI and public-API `list`, `query`, and `context` calls run
`check_repo_staleness` before returning indexed data. The checker reads `File`
rows, stats each indexed path, walks the current repo for supported Clojure, Java, and C files, and
reports the elapsed time in a leading `note:` or `warning:` line. These direct
reads return stale results with the warning; `sql` skips the check and remains
raw read-only DB access.

MCP is intentionally different. Its `query`, `context`, and `sql` tools accept
an optional `repo` naming a worktree root or any path inside it; omission uses
the server working directory. `mcp_repo_session_open` resolves the canonical
worktree and transparently prepares a current database through the ordinary
staging-and-rename path. It never returns a known-stale fallback: preparation
failure becomes one generic search-availability result. Successful results do
not include build, repair, freshness, timing, or cache notes. Index inventory
and removal remain available through the CLI/public API but are not MCP tools.

- `code_lens_list_repos`: walks `<home>/repos/`, opens every published
  `index.sqlite` in sorted directory order, and concatenates
  `SELECT ... FROM Repo ORDER BY name` results, emitting the header line
  once. Directories holding only a legacy pre-SQLite `index.lbug` are
  skipped with a stderr warning that a re-index is required. With no
  readable repos it returns `No readable repositories indexed...`.
- `code_lens_query_symbols` (the `query` command):
  - Tokenizer (`tokenize`): lowercased tokens of at least 2 characters, at
    most 16 tokens (`MAX_QUERY_TERMS`), token characters are alphanumerics
    plus `_ - ? ! * . /`. Tokens without any alphanumeric character are
    dropped (they cannot produce FTS words); if nothing usable remains the
    call returns the no-match message.
  - Each term becomes a quoted FTS5 prefix phrase: internal `"` doubled,
    wrapped in double quotes, suffixed with `*` (for example
    `"gen-fn-11-17"*`). Terms are space-joined, which FTS5 treats as AND
    (`build_fts_match`). Quoting keeps FTS5 operators in user input inert.
  - When the AND pass returns zero rows and at least two terms were usable,
    the same terms are re-joined with ` OR ` and the query reruns; BM25
    naturally ranks rows matching more terms first. A relaxed result is
    prefixed with the notice line
    `note: no symbols or keywords match all terms; showing any-term matches`
    followed by a blank line. If the OR pass also finds nothing, the ordinary
    no-match message comes back.
  - One query joins `SymbolFts` matches back to `Symbol` and ranks with
    BM25: `ORDER BY bm25(SymbolFts), s.name`, exposing
    `CAST(-bm25(SymbolFts) * 100 AS INTEGER) AS score` (higher is better).
    A second query searches `Keyword.keywordBase` by prefix and returns usage
    locations. Its filtered rows receive `ROW_NUMBER()` within each file and
    are ordered by workspace/dependency scope and then occurrence number, so
    within each scope the first match from every file precedes second matches
    from any file. The query fetches one row beyond `limit`; when that row
    exists, rendering stops at `limit` and adds an actionable note to narrow
    with `path`/`--path` or increase `limit`. Results are rendered as separate
    `Symbols` and `Keywords` sections; the limit applies independently to each
    section. The CLI and MCP tool both honor `limit`.
  - `CodeLensQueryOptions` drives optional filtering. `exclude_tests` removes
    `Symbol.kind = 'test'` rows and rows whose normalized path contains
    `/test/` or `/tests/`; keyword rows use only the path part. Symbol kinds
    apply to symbols with exact `s.kind = ?` and suppress keyword rows because
    keywords have no kind; the pseudo-kind `keyword` returns the Keywords
    section only, and an unrecognized kind prepends a note naming the valid
    values (see `query_kind_list`). `path` is a case-insensitive substring
    match on `filePath` for both symbol and keyword rows.
  - Scope filtering joins `DependencyFile`/`DependencyArtifact` only after the
    FTS match. `workspace` is the default and requires no dependency mapping;
    `dependencies` requires one; `all` allows both and orders workspace rows
    first. `dependency` is a bound SQLite `GLOB` against the resolved GAV. Symbol
    and keyword rows expose their origin and coordinate.
  - Matching is word- and prefix-based, not substring-based: `alid` does not
    match `validator`. This is a deliberate FTS trade-off; the tokenizer's
    extra separators make Clojure name parts (`refund`, `date`, `type`, ...)
    first-class words.
- `code_lens_context_symbol_ex`: two bound queries stitched into a text
  report with `Definitions` and `References` headings
  (`code_lens_context_symbol` delegates with default options):
  - definitions: `s.name = ?2 OR instr(lower(s.name), lower(?2)) > 0`, exact
    matches ranked first, `LIMIT 5`. `s.content` (full source for Clojure and
    Java members; a compact declaration header for Java types) is returned
    only for exact-name matches; substring-fallback
    rows keep name/kind/location/doc but an empty content column, so
    near-miss lookups don't bury the answer under unrelated bodies;
  - references: `Ref` rows matched on `symbolBase`. Clojure `alias/name` and
    Java `Type.member`, `Type#member`, fully qualified type input, or C
    `ptr->field` input is reduced to its final component when no exact dotted definition exists. Unqualified
    references count only where they can resolve: the file's own namespace
    defines the symbol, or the file has a `Referred` row (exact symbol or the
    `:all` sentinel) from a namespace/type that defines it. Resolved references
    count only when `targetNamespace` matches a namespace that actually defines
    a symbol of that name, filtering same-name definitions in unrelated
    namespaces. Clojure `:rename` and Java compiler-level dynamic attribution
    are not tracked. Rows carry
    `r.startByte`, `r.endByte`, `r.symbol`, and `r.targetNamespace` for
    inspection and are ordered by `filePath`, `lineNumber`, `LIMIT 25`.
    Context also appends a `Reference snippets` section rendered lazily from
    the current source files by mapping each referenced file and scanning
    around the stored byte span; snippets are not stored in SQLite.
  - candidate scope is workspace-first. If no workspace definition survives
    the name/namespace/path/test filters, context reruns against dependency
    candidates and prepends an explicit fallback note. A Java type qualifier
    is used to infer a unique fully qualified enclosing type. References from
    the workspace and from dependency sources remain visible, carry separate
    origin/coordinate columns, and sort workspace-first. If `path` names a
    workspace calling file with no definition, dependency fallback treats it
    as source context rather than filtering external definition paths.
  - `exclude_tests` (MCP `excludeTests`, CLI `--exclude-tests`) applies
    query's test-path predicate (`/test/`, `/tests/`) to all three queries
    and additionally drops `test`-kind definition rows.
  - `namespace` (CLI `--namespace`) and `path` (CLI `--path`) filter the
    definition candidates by namespace and case-insensitive file-path
    substring before reference resolution. They therefore return only
    references that resolve to the selected candidates, rather than merely
    filtering reference-site rows.
- `code_lens_run_sql` (the explicit `sql` command/public API): checks the
  repo exists (unknown repos get the "unknown repo ... indexed repos: ..."
  message), then runs the query on a read-only connection with an
  `sqlite3_set_authorizer`
  installed that allows `SQLITE_SELECT` / `SQLITE_READ` / `SQLITE_FUNCTION`
  / `SQLITE_RECURSIVE` and denies everything else -- `ATTACH` (which would
  read arbitrary SQLite files on disk), `PRAGMA`, writes, and schema or
  connection state changes all fail to prepare with `not authorized`,
  surfaced through the `SQL error:` path. Row output is capped in the
  render loop at `SQL_TOOL_MAX_ROWS` (100) with a trailing
  `note: output capped at 100 rows` line, so no SQL-text heuristic is
  involved and a trailing semicolon changes nothing. Only the first
  statement of a multi-statement string runs (prepare stops at `;`). The
  progress-handler deadline backstops runaway queries.

## 14. CLI reference

Dispatcher: `code_lens_cli_main`. Usage text (`print_usage`):

```
code-lens 0.1.0-dev (ISO C23)

Usage:
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

- Exit codes: `0` success, `1` operation failed, `2` usage error or unknown
  command. No arguments prints usage to stdout and exits 0; an unknown
  command prints `code-lens: unknown command '<x>'` plus usage to stderr and
  exits 2.
- `version` prints `0.1.0-dev` (`CODE_LENS_VERSION`). `version --deps` adds
  `sqlite <version>`, `tree-sitter-clojure abi=<n> symbols=<n>`,
  `tree-sitter-java abi=<n> symbols=<n>`, and
  `tree-sitter-c abi=<n> symbols=<n>` lines.
- `index` requires `--repo <path>` (`--path` is accepted as a legacy
  synonym); success prints exactly
  `indexed <path>: <N> files, <N> symbols, <N> references, <N> keywords, <N> aliases in <T>s (git <B>/<N>)`
  where `<path>` is the canonical repo path, `<T>` is the wall time of the
  whole run with millisecond precision
  (`CodeLensIndexStats.elapsed_seconds`), kept visible so performance
  regressions are noticed immediately, and `<B>` is how many of the `<N>`
  files were served from the git object store during this run
  (`CodeLensIndexStats.git_blob_reads`, a per-run delta of the process
  counter) — `git 0/…` on a non-git tree or with `CODE_LENS_GIT_BLOBS=0`,
  and below `<N>` when dirty/untracked files fell back to disk. This line is
  parsed by
  `scripts/benchmark-index.sh` and `scripts/ab-bench.sh` (their `sed`
  patterns tolerate any suffix after `aliases`); treat its format as an
  interface. Because a repo is keyed by canonical path, re-indexing the
  same tree always replaces the same database — two names over one working
  tree can no longer happen.
- `remove` requires `--repo <path>` and deletes the whole repo directory,
  including any legacy pre-SQLite artifacts inside it.
- `context` accepts the symbol either as `--name <symbol>` or as positional
  text. Its `--namespace <namespace>` and `--path <substring>` filters select
  definition candidates (and therefore only references resolving to them), not
  merely reference-site output.
- Argument parsing is minimal: `arg_value` scans for `--flag value` pairs;
  `free_arg_text` joins all non-flag arguments into one space-separated
  string, skipping `--repo`/`--name`/`--limit` and their values.

## 15. MCP server

`code-lens mcp` (`code_lens_mcp_main`) runs a JSON-RPC 2.0 server on
stdio. Protocol version `2024-11-05`, server name `code-lens`, capabilities
`{"tools":{}}`.

- Framing: auto-detected per message. If a line starts with
  `Content-Length:`, the server reads an LSP-style header-framed body and
  responds with the same framing; otherwise it treats input as
  newline-delimited JSON and responds with a newline-terminated line. The
  response framing always mirrors the request.
- JSON handling is a small hand-rolled scanner (no JSON library): key lookup,
  string/int extraction, raw `id` preservation, and a brace/string/escape
  tracking extractor for the `arguments` object.
- The main loop takes an arena mark before each message and resets afterwards,
  so the server's memory stays flat regardless of uptime.
- Methods (`handle_message`): `initialize`, `notifications/initialized`
  (ignored), `tools/list`, `tools/call`. Error codes: `-32600` missing
  method, `-32601` unknown method, `-32602` missing tool name/arguments,
  `-32603` tool failure or out-of-memory.
- Tools (`respond_tools_list`), all returning a single text content block:

| Tool | Arguments | Internal operation |
| --- | --- | --- |
| `query` | `query` (required), `repo` (default `.`), `limit` (default 10), `excludeTests` (default false), `kind`, `path`, `scope`, `dependency` | prepares the worktree, then calls `query_symbols_ex_internal` with its open database |
| `context` | `name` (required), `repo` (default `.`), `excludeTests` (default false), `namespace`, `path` | prepares the worktree, then calls `context_symbol_ex_internal` with its open database |
| `sql` | `query` (required), `repo` (default `.`) | prepares the worktree, then calls `query_with_open_repo_db` |

There are exactly three MCP tools: `query`, `context`, and `sql`. Cache
inventory and removal are intentionally CLI/public-API concerns. `repo` can be
a root, descendant directory, or file in a non-bare Git worktree. Initialization
and `tools/list` do not enumerate cached repositories or mention storage
status, and successful calls do not prepend maintenance notes. This keeps the
model-facing contract equivalent to a source search rooted at the requested
path.

Client registration (Copilot CLI example, from `USAGE.txt`):

```sh
copilot mcp add code-lens -- \
  env CODE_LENS_HOME=/path/to/home \
  /path/to/code-lens/build/code-lens mcp
```

`CODE_LENS_HOME` must be embedded in the registered command (the `env ...`
prefix above). A shell export made before `copilot mcp add` is not stored in
the MCP configuration, and the server would fall back to `~/.code-lens`.
A long-running MCP server holds no database handles between requests, but it
must be restarted to pick up a new binary.

## 16. Testing

Run with `make test` (current/default mode), `make test-release`, or
`make test-debug`. The binary is `build/tests/test-main`. There is no test
filter or per-test target; focused test work means adding or narrowing
assertions in the test files and rerunning the suite.

Harness mechanics:

- The Makefile compiles `src/code_lens.c` a second time with
  `-DCODE_LENS_NO_MAIN` into `code_lens_test.o`. The application `main` is
  guarded by `#ifndef CODE_LENS_NO_MAIN`, so `tests/test_main.c` provides
  the test `main` while linking the entire application and vendor objects.
- `tests/test_main.c` checks: `CODE_LENS_C_STANDARD == "ISO C23"`, a
  non-empty `CODE_LENS_VERSION`, a non-empty SQLite version string, and
  non-zero tree-sitter Clojure, Java, and C ABI versions and symbol counts;
  then calls `test_clojure_parser()`, `test_java_support()`, `test_c_support()`,
  `test_index_readback()`, and
  `test_context_alias_resolution()`.
- `tests/test_clojure_parser.c` parses an inline Clojure fixture string (no
  fixture files on disk) containing an `ns` form with two requires, a `defn`
  with a docstring, a `defmethod`, a `deftest`, metadata-annotated `def`s
  (`^:private`, map metadata, old-style `#^:legacy`), and a
  metadata-annotated alias-qualified reference. Assertions cover the
  extracted namespace, alias count and first alias pair, presence and kinds
  of the definitions, exact docstring text, the `defn` start line, that
  references outnumber symbols, that no extracted symbol or reference text
  starts with a metadata marker, and that a qualified reference is stored as
  clean `alias/name`. The local `assert_true` accumulates failures rather
  than stopping at the first one.
- `tests/test_java_support.c` parses a Java fixture and checks package/import,
  static-import, type/member/Javadoc extraction and receiver target resolution.
  Its end-to-end fixture verifies the generic walker, Java indexing, a `class`
  kind filter, SQL visibility, static-import context, Java-file staleness,
  incremental addition, and `context` linking `service.execute` back to
  `demo.lib.Service.execute` when called with the dotted name `Service.execute`.
- `tests/test_c_support.c` checks C macros, typedefs, aggregates, function
  pointers, globals, static/external linkage, documentation, calls, and typed
  member references. Its end-to-end fixture verifies `.c`/`.h` walking,
  `struct` filtering, global/static/member context, C-file staleness, and an
  incremental source addition.
- `test_index_readback` generates a fixture repo (12 files, >2048 symbols,
  >131072 references) in a temp directory with an isolated
  `CODE_LENS_HOME`, indexes it, and verifies through the public API:
  exact `COUNT(*)` and `COUNT(DISTINCT id)` read-backs per table via `sql`,
  `context` reachability of a late symbol, FTS `query` full-token and
  prefix matches, the multi-term OR fallback (notice line plus surviving
  match) and its all-bogus no-match counterpart, the no-match and
  unknown-repo messages, primary-key equality lookups and a Symbol id
  round-trip, fresh and stale index warnings with elapsed staleness-check
  timing, second-repo isolation, re-index reproducibility, and `remove`
  semantics.
- `test_repository_write_lock` holds one repo's writer lock in the parent,
  forks two MCP query processes against a stale index, proves neither can
  finish while the lock is held, then verifies both return the newly indexed
  symbol without exposing refresh status. The first waiter refreshes and the
  second rechecks under the lock and takes the current-index no-op path.
- `test_context_alias_resolution` builds a four-file fixture (a definition,
  an alias-qualified caller, a fully-qualified caller, and a same-named
  definition in an unrelated namespace plus a reference whose alias resolves
  to a namespace defining nothing) and asserts the `Ref` rows carry the
  expected `symbolBase`/`targetNamespace` values and byte spans, that `context`
  lists the alias-qualified and fully-qualified call sites with lazy snippets,
  that it excludes
  references whose qualifier resolves to a namespace without the
  definition, and that a user-typed qualifier is stripped for the lookup.

There is no CI; run the tests yourself before and after changes, in both
modes when the change touches build flags or vendor code.

## 17. Benchmarking and performance workflow

### The benchmark harness

```sh
scripts/benchmark-index.sh --path <repo-path> \
  [--runs 5] [--warmups 1] \
  [--expect-files N] [--expect-symbols N] \
  [--expect-references N] [--expect-keywords N] [--expect-aliases N]
```

The script requires an existing `build/code-lens`, creates an isolated
throwaway `CODE_LENS_HOME` per run under `$TMPDIR`, times each `index`
invocation, parses the `indexed ...` output line, fails on any `--expect-*`
mismatch, verifies (outside the timed window) that the produced index
surfaces the same per-table row counts through `sql` read-back, and prints
per-run TSV rows plus a `summary median=... mean=... min=... max=...` line
computed over the measured runs (warmups excluded).

### Comparing two binaries (thermal drift)

Sequential run batches on a laptop are not comparable: the machine heats up
and CPU frequency limiting punishes whichever binary runs second. For any
old-vs-new comparison use the interleaved A/B harness instead:

```sh
scripts/ab-bench.sh --a <old-binary> --b <new-binary> --path <repo-path> \
  [--pairs 5] [--expect-* N ...]
```

It alternates single runs A/B/A/B with cooldown sleeps, applies the same
`--expect-*` count validation per run, and reports per-binary medians plus
the median delta. Interleaving cancels slow thermal drift; medians resist
outliers. Keep the pre-change binary at a scratch path (plain `cp` to a NEW
path is fine; never overwrite an executed binary in place) and revert
changes whose median regresses beyond noise (>2% is the working threshold).

### MCP smoke gate

`scripts/mcp-smoke.sh` is the pre-commit end-to-end gate: it drives
`build/code-lens mcp` (override with `CODE_LENS_BIN`) over newline-delimited
JSON-RPC against the committed `tests/fixtures/mcp-smoke` mixed
Clojure/Java/C fixture. It covers initialize, tools/list, default/descendant
repository resolution, and Clojure, Java, and C query/context and SQL. It
asserts change-insensitive semantic facts (a known symbol resolves to a known
path and `sql` sees the expected `Symbol` count) and verifies that cache and
freshness details are absent from MCP metadata. Run it before every commit
that touches indexing, storage, or MCP code.

### Regression counts and the benchmark fixture

The indexing counts double as a correctness oracle for parser and walker
changes: a change that alters the `indexed ...` counts on a fixed input has
changed extraction behavior, not just performance.

The customary local fixture is a large Clojure monorepo checked out next to
this repository, for example `../big-repo` (roughly 2,000 files,
18,800 symbols, 452,000 references, 245,000 keywords, and 13,000 aliases as
of 2026-07-07; a from-scratch index build of it takes ~0.25s warm on an
M-series laptop, ~8x faster than before the 2026-07 optimization passes —
custom Clojure reader, dictionary encoding, FTS sidecar with raised
hashsize, streamed walk, the raw b-tree emitter with post-COMMIT graft,
side-thread index prep, parallel graft writes, and friends).
Any sizable Clojure codebase works. Live repositories drift over time, so
before comparing counts or timings, re-baseline with the pre-change binary:
run the benchmark script without `--expect-*` flags (or a plain `index`) to
learn the current counts, then hold those fixed across the change with the
`--expect-*` flags. Any count difference introduced by a change is a
regression until explained.

### Performance methodology

- Measure phases before naming a root cause. Useful signals:
  `CODE_LENS_PROFILE=1` phase timings (db open/schema, walk, read, parse,
  per-table write, repo write, FTS rebuild, commit, publish), the final
  `indexed ...` counts, the staging/published `index.sqlite` size, and a CPU
  sample during the suspected phase.
- Run `make clean && make release` before performance comparisons whenever
  build flags, LTO flags, or SQLite defines changed.
- For profiling with symbols, build with
  `make release OPTFLAGS='-O3 -g -DNDEBUG -march=native' LTOFLAGS=` and index
  with `CODE_LENS_INDEX_THREADS=1` so the sampler has usable stacks. For
  very short jobs, prefer the benchmark script's exact counts and median
  timings over a sparse sampler report.
- After any write-path or schema change, validate both the counts and read
  behavior: reopen the index and smoke `list`, `query`, `context`, and
  `sql`.

## 18. Environment variables

| Variable | Read at | Effect |
| --- | --- | --- |
| `CODE_LENS_HOME` | `code_lens_default_home` | Overrides the home directory containing `repos/<repo-dir>/index.sqlite`. Empty counts as unset. |
| `HOME` | same | Fallback base for the default home `~/.code-lens`. If both are unset, home resolution fails. |
| `CODE_LENS_INDEX_THREADS` | `configured_index_thread_count` | Index worker count. Unset: `min(CPUs, 8)`. `0`/`1`: sequential path. `>64`: clamped to 64 with a warning. Invalid: warning, default used. |
| `CODE_LENS_EMITTER` | `raw_emitter_enabled` | `classic` selects the prepared-INSERT bulk load. Unset, empty, or anything else: the raw b-tree emitter (default). Raw failures always retry with the classic emitter. |
| `CODE_LENS_RAW_INDEXES` | `raw_indexes_enabled` | Classic emitter only: `0` builds secondary indexes with plain `CREATE INDEX` instead of the post-COMMIT raw b-tree graft. |
| `CODE_LENS_FTS_SIDECAR` | `fts_sidecar_enabled` | `0` disables the sidecar FTS thread. Classic emitter then rebuilds FTS in place; the raw emitter requires the sidecar and fails over to classic. |
| `CODE_LENS_PARSER` | `code_lens_clojure_parser_new` | `treesitter` forces the tree-sitter parser backend; default is the built-in reader with tree-sitter fallback. |
| `CODE_LENS_GIT_BLOBS` | `git_blobs_enabled` | `0` disables the git blob source; files are always read from the working tree. Default: clean tracked files are served from `.git` objects. |
| `CODE_LENS_PROFILE` | profile init in the indexer | Any non-empty value other than `0` enables index phase timing output. |
| `TMPDIR` | `scripts/benchmark-index.sh`, `scripts/detect-c23-flag.sh` | Scratch directory base, defaults to `/tmp`. |

## 19. Invariants checklist

The condensed list of rules that must survive any change:

- **Language and warnings.** Project sources stay ISO C23, compiled with
  `-Wall -Wextra -Wpedantic -Werror -pedantic-errors`. The toolchain is pure
  C end to end; the final link uses the C compiler.
- **Arena discipline.** Never free arena pointers; lifetimes are marks and
  resets only; no no-op cleanup helpers. Preserve real cleanup for
  tree-sitter objects, `sqlite3_finalize`/`sqlite3_close`/`sqlite3_free`,
  `munmap`, `closedir`, file descriptors, and pthread primitives.
- **Statement teardown.** Every prepared statement is finalized before its
  connection closes.
- **Binding lifetimes.** `SQLITE_STATIC` only for arena-backed values that
  outlive the step; stack buffers bind with `SQLITE_TRANSIENT`.
- **Format stamp.** The schema carries `PRAGMA user_version =
  CODE_LENS_INDEX_FORMAT_VERSION`; read opens reject mismatches with a
  "re-index required" message. Bump the version on any schema change.
- **Build pragmas.** `journal_mode=OFF`/`synchronous=OFF` apply only to
  staging files published by atomic rename, never to databases opened in
  place.
- **Threading.** `CODE_LENS_INDEX_THREADS=1` must keep forcing the
  sequential path; default cap 8, hard cap 64; parser and arena are
  per-worker; emission stays single-threaded and in file order (Ref IDs are
  order-dependent ordinals). One thread per connection at a time
  (`SQLITE_THREADSAFE=2`).
- **Parser matching.** Length-checked source-slice comparisons for literals
  (`:require`, `:as`, `ns`, definition heads); symbol names come from grammar
  field access (`symbol_value_slice`), which strips metadata nested inside
  `sym_lit` nodes; definition detection compares the `name` field child (the
  base name after the namespace delimiter). The benchmark fixture's exact
  counts are the regression oracle: re-baseline with the pre-change binary,
  then hold the counts fixed across the change.
- **Callback path lifetime.** `CodeLensFileCallback` paths are valid only
  during the callback; copy anything kept.
- **Size guards.** Sources larger than `UINT32_MAX` bytes are rejected before
  tree-sitter parsing.
- **Query safety.** Application queries bind parameters; user input is never
  interpolated into SQL. Reads run on read-only connections with the
  progress-handler deadline; the `sql` command additionally installs an
  authorizer (SELECT/READ/FUNCTION/RECURSIVE only; ATTACH, PRAGMA, and
  writes are denied) and caps rendered output at 100 rows.
- **FTS coupling.** `SymbolFts` is external-content over `Symbol` and must be
  rebuilt after the bulk load; schema changes to `Symbol` must keep the FTS
  column list and `content_rowid` in sync.
- **Output format.** The pipe renderer's shape (header line, `|` separators,
  newline-terminated rows, NULL as empty) is an interface consumed by tests
  and the benchmark script, as is the `indexed ...` summary line.
- **Test hook.** `main` stays guarded by `#ifndef CODE_LENS_NO_MAIN` so the
  test build can relink the application under `tests/test_main.c`.
- **Vendoring.** Dependency source trees are never committed; pins live in
  `vendor/deps.lock` (git commits, or the archive SHA-256 for the SQLite
  amalgamation). No vendor patches.
- **Binary copies.** `build/code-lens` and `build/tests/test-main` are
  removed before being re-copied; overwriting an executed binary in place
  trips the macOS kernel's stale code-signature cache and the new binary is
  killed on exec.
- **Isolation.** Smoke tests, benchmarks, and profiling runs use an isolated
  `CODE_LENS_HOME`, never the default `~/.code-lens`.
