# code-lens improvement plan

Source: `../feedback.md` (agent friction report from a real cross-repo
PR-review session), **with item 4 superseded** by later source inspection of
`src/code_lens.c`. This plan covers the items owned by **code-lens**. A
companion plan, `../coding-agent-plan.md`, covers the coding-agent Java
project; the two plans are independent and can be executed by separate agents
with no ordering constraints between them.

## Background: what actually happened (item 4, corrected)

During the review session, the decisive lookup was "what does
`baselayer.validation/build-validator` do?" — `baselayer` being a Clojure
library dependency of `taxamo-tax-id-checker` (a **Leiningen** project; the
sibling repo `taxamo-apparatus` is **tools.deps/Polylith**). The original
feedback blamed agent habit for not trying `code-lens_query` with
`scope: dependencies`. Source inspection shows that call could not have
succeeded, for three independent reasons:

1. **Gate:** `maven_dependencies_prepare` (~line 14790) requires a root
   `pom.xml` (`set->root_pom = join(repo, "pom.xml"); if (!exists) return 0;`
   with `set->active` false). Leiningen/deps.edn repos never start dependency
   resolution.
2. **Classifier:** `maven_resolve_sources` (~14536) runs
   `maven-dependency-plugin:copy-dependencies -Dclassifier=sources` and
   `maven_source_jar_callback` (~14431) keeps only `has_source_jar_suffix`
   (`"-sources.jar"`, ~13848). Clojure libraries ship source in the **main
   jar** (verified: the baselayer artifact in `~/.m2` contains
   `baselayer/validation.clj` directly; no `-sources.jar` exists next to it).
3. **File filter:** dependency file collection is Java-only —
   `maven_artifact_push` (~14401) and `maven_source_cache_valid` (~14254)
   collect with `has_java_extension`, and jar extraction itself hard-rejects
   non-`.java` entries in `zip_java_path_safe` (~14026's helper). Even a
   resolved Clojure jar would contribute zero files, despite the indexer
   being fully language-aware for workspace files (dependency files go
   through the ordinary `index_file_callback` → `has_supported_extension`
   pipeline).

Worse, the failure is **silent twice over**: with no `pom.xml`,
`insert_maven_metadata` (~14850) early-returns on `!set->active`, so no
`MavenProject` row exists, and `maven_project_status_note` (~17532) emits
nothing. A `scope: dependencies` query on such a repo returns a bare
"no symbols…" with zero explanation.

The plan below fixes visibility first (cheap), then the root cause.

---

## 1. Make the dependency-scope gap visible (small — do first)

**Goal:** "nothing matched" must be distinguishable from "this repo has no
dependency index and here is why" — the same principle as the grep-glob item
in the coding-agent plan.

**Changes.**

- In `maven_dependencies_prepare`, when the root `pom.xml` is absent, do not
  bail out inert. Instead detect known non-Maven build files and record a
  status row:
  - `project.clj` at the repo root → `status = "unsupported"`,
    `message = "no pom.xml; found project.clj (Leiningen), which is not yet supported for dependency sources"`.
  - `deps.edn` at the root → same with `deps.edn (tools.deps)`.
  - Neither → `status = "none"`, `message = "no pom.xml; dependency sources are only resolved for Maven projects"`.
  - Set `set->active = true` for the metadata write only (no inputs, no
    artifacts). Adjust `insert_maven_metadata` so a project row with zero
    inputs/artifacts is valid, and adjust `maven_load_existing_artifacts` /
    `maven_try_reuse` so `"unsupported"`/`"none"` rows are reusable without
    re-running detection work, but are **not** treated as permanently cached
    once support ships (mirror the existing comment's rule for
    `"disabled"`: reusable only while the condition still holds — i.e.
    re-check which build files exist when reusing).
- `maven_project_status_note` already renders any non-`"resolved"` status
  into query/context output and `list_repos` (via `mcp_join_session_note`,
  ~17561). With the row now present, the note appears automatically — verify
  and keep its `note:`/`warning:` prefix logic (`"unsupported"`/`"none"`
  should be `note:`, not `warning:`).
- In the query path, when the caller **explicitly** passed
  `scope: dependencies` or `scope: all` (options parsing near 18147 and the
  MCP arg handling near 19548) and the repo has zero `DependencyFile` rows,
  prepend the status note even on the empty-result path, so the agent's very
  first misdirected call is self-explaining.

**Index-format note.** No schema change (rows only), so
`CODE_LENS_INDEX_FORMAT_VERSION` (2472) does **not** need a bump; existing
indexes simply lack the row until their next rebuild, which the standard
staleness path handles.

**Tests** (`tests/test_java_support.c` hosts the existing Maven tests and the
fake-`CODE_LENS_MAVEN_COMMAND` pattern around line 548; follow it):

- Repo with `project.clj` and no `pom.xml`: index succeeds; `sql` shows the
  `MavenProject` row with `status = "unsupported"`; `query` with
  `scope: dependencies` output contains the note text.
- Repo with neither: `status = "none"` row and note.
- Reuse: second index run does not repeat detection side effects and keeps
  the row.

---

## 2. Truth in tool descriptions (trivial — same PR as item 1)

The MCP descriptions (string literals near 19449/19463–19464, plus the
`query`/`context` tool descriptions and `README.md`/`AGENTS.md`) say
"resolved Maven dependency sources" without stating the current constraints.
Until item 3 ships, append to the `scope` and `dependency` parameter
descriptions and the `query` tool description:

> Dependency sources are currently resolved for Maven (`pom.xml`) projects
> only; Leiningen/deps.edn repositories have no dependency scope.

When item 3 lands, update the same strings in the same commit that changes
the behavior (stale capability claims were the original trap). Keep the
descriptions short; the per-repo status note from item 1 carries the
detailed message.

---

## 3. Clojure dependency resolution (the root-cause fix — moderate)

**Goal:** `code-lens_context name: baselayer.validation/build-validator` on a
Leiningen or deps.edn repo answers with the dependency definition, via the
**existing** automatic workspace→dependencies fallback in
`code_lens_context_symbol` (~18810: `definition_scope` selection and the
"note: no workspace definition matched; showing Maven dependency definitions"
banner). No new tool surface. The context-tool UX is already right; only the
front-end resolution is missing.

Almost all machinery exists and must be reused, not duplicated:

- subprocess runner with timeout: `run_process_with_timeout` (~13600)
- jar extraction + FNV checksum cache + advisory locking:
  `maven_materialize_source_jar` (~14271) and `maven_source_cache_valid`
- `~/.m2` repository-layout → GAV parsing: `maven_source_jar_callback`
- input snapshotting / staleness: `maven_collect_inputs`,
  `maven_inputs_match_db`, `MavenInput` table
- multi-language dependency indexing: `index_maven_dependency_files` feeds
  `index_file_callback`, which already dispatches Clojure/Java/C by
  extension.

### 3a. Build-file detection and input tracking

Extend `maven_collect_inputs`/`maven_dependencies_prepare` (or a thin
`dependency_project_detect` wrapper) to recognize, in priority order at the
repo root: `pom.xml` (existing path, unchanged), else `project.clj`
(Leiningen), else `deps.edn` (tools.deps). Record the chosen build file as
the project root marker (reuse the `rootPom` column; it is a path string —
document that it now names whichever build file anchors resolution, and
update the `sql` tool's table description text accordingly).

Track as `MavenInput` rows for staleness: the root build file, plus
`profiles.clj` and `.lein/**` equivalents are **out of scope** (user-global);
for deps.edn also track root `deps.edn` only (aliases from `~/.clojure` are
out of scope, same trust rationale as the existing "automatic Maven execution
is only safe for trusted repositories" rule in AGENTS.md). Keep the existing
rule that ordinary source refreshes never rerun resolution when the snapshot
matches.

### 3b. Resolution via classpath, not copy-dependencies

For Leiningen: run `lein classpath` (respect a new
`CODE_LENS_LEIN_COMMAND` override, mirroring `CODE_LENS_MAVEN_COMMAND` — the
override is also what makes hermetic tests possible). For tools.deps: run
`clojure -Spath` (override `CODE_LENS_CLOJURE_COMMAND`). Both print a
platform-separator-joined classpath; the classpath **is** the resolved
dependency set:

- split on `:` (`;` on Windows),
- keep entries ending in `.jar` that live under the local artifact
  repository (contain `/repository/` for m2 layout or `/.gitlibs/` — keep
  the filter permissive: any absolute jar path is acceptable, since GAV
  parsing below decides what is representable),
- ignore directory entries (those are the workspace's own source paths).

Derive GAV per jar by reusing the repository-layout parsing logic (the last
three path components before the jar are `artifactId/version/file` and the
preceding components under the repository root form the groupId — the same
walk `maven_source_jar_callback` does over the copy-dependencies output
tree). Jars that don't parse increment `skipped_artifacts` exactly as today.

For each jar, **prefer** a sibling `<name>-sources.jar` when it exists
(Java-ecosystem deps in a Clojure project still get real sources); otherwise
materialize from the **main jar** (Clojure libraries). Reuse the existing
timeout (`maven_timeout_ms` — generalize the name or add a shared default),
the `CODE_LENS_MAVEN=0` kill switch (keep the single env var as the global
dependency-resolution kill switch; document that it now also disables
lein/clojure execution, or introduce `CODE_LENS_DEPS=0` as the umbrella with
`CODE_LENS_MAVEN=0` kept as an alias — pick one and document it), and the
failure-isolation rule: resolution failure must not replace an existing
published index (`maven_dependencies_prepare`'s existing
`had_index → return -1` shape).

Scope/direct metadata: classpath output does not distinguish scopes or
directness; store `scope = "classpath"` and `direct = -1` (the existing
"unknown" convention noted in AGENTS.md).

### 3c. Multi-language extraction and collection

- Generalize `zip_extract_java_sources` → `zip_extract_source_entries` with a
  suffix predicate parameter; accept the supported-source set already defined
  by the platform helpers (`.clj`, `.cljc`, `.cljs`, `.bb`, `.java` — leave
  `.c`/`.h` out of jar extraction unless trivially free, jars don't carry
  them). `zip_java_path_safe` keeps all its traversal/character checks;
  only the extension test moves into the predicate.
- Swap `has_java_extension` → `has_supported_extension` at the three
  dependency collection sites: `maven_artifact_push` (~14401),
  `maven_source_cache_valid` (~14254), and any marker-count logic tied to
  extraction counts (the marker file records count + checksum; extraction
  and validation must use the same predicate or every cache check fails —
  add a test for exactly this).
- `index_maven_dependency_files` needs no change: `index_file_callback`
  already parses `.clj` files (namespaces, defs, refs, keywords), and
  `File.namespace` population gives dependency Clojure namespaces for free,
  which is what makes `context` alias resolution (`Alias`/`Referred` joins)
  work against dependency definitions.

**Cache invalidation:** materialized source roots are keyed by jar checksum
(`.../dependencies/sources/<group>/<artifact>/<version>/<fnv64>`), so
previously-cached Java-only extractions of main jars are naturally distinct
from nothing (main jars were never extracted before). No migration needed.
The **extraction marker** format is unchanged (hash + count), but since the
predicate changes what counts, bump nothing — old markers simply fail
validation and re-extract, which is the designed behavior.

**Index-format:** no schema change; `DependencyArtifact`/`DependencyFile`
columns are reused as-is. Do not bump `CODE_LENS_INDEX_FORMAT_VERSION`.
Existing Maven repos' behavior must be byte-identical when a `-sources.jar`
exists for every artifact (regression-guard this with the existing fake-mvnw
test fixtures).

### 3d. Tests (follow the fake-command pattern in `tests/test_java_support.c` ~548)

- **Fake lein:** a shell-script `lein` (installed via
  `CODE_LENS_LEIN_COMMAND`) that prints a classpath pointing at a fixture
  m2-layout tree containing (a) a main jar with a `.clj` entry defining a
  function, (b) a jar pair where `-sources.jar` exists. Assert:
  - index succeeds; `MavenProject.status = "resolved"`;
  - `query` `scope: dependencies` finds the Clojure defn with its
    `dependency` GAV filter working (`k.dependency` rendering, ~18133);
  - `context` on the qualified name (`some.ns/some-fn`) with **no** workspace
    definition triggers the dependency fallback and the banner line;
  - the sources-jar artifact indexed the `-sources.jar` content, not the
    main jar.
- **Fake clojure:** same fixture via `CODE_LENS_CLOJURE_COMMAND` and a
  `deps.edn` root.
- **Staleness:** touching root `project.clj` re-resolves; touching an
  ordinary workspace `.clj` does not (assert via the fake command's
  invocation-count file, mirroring the existing `count_path` trick).
- **Kill switch:** `CODE_LENS_MAVEN=0` (or the chosen umbrella var) yields
  `status = "disabled"` with the lein/clojure message, and enabling later
  resolves without a build-file edit (the existing `"disabled"` reuse rule).
- **Failure isolation:** fake lein exiting nonzero on a repo with an existing
  index leaves the published index intact.
- **Mixed repo guard:** repo with both `pom.xml` and `project.clj` uses the
  Maven path only (priority order), asserted by the fake commands' count
  files.
- **Windows:** classpath split on `;` — unit-test the splitter directly.

### 3e. Follow-through

- Update `README.md`, `AGENTS.md` (the Maven paragraph in Architecture), and
  the MCP description strings from item 2 in the same PR.
- Remove the "unsupported (Leiningen/tools.deps)" wording from item 1's
  status messages for the now-supported cases; the `"unsupported"` status
  remains for genuinely unknown layouts (no recognized build file →
  `"none"`).

---

## Explicit non-goals

- **Boot, shadow-cljs, Gradle, npm** resolution — out of scope; they fall
  into item 1's honest `"unsupported"`/`"none"` reporting.
- **Per-alias/per-profile classpaths** — resolve the default profile/alias
  set only; this matches the existing Maven behavior of one reactor-wide
  `test-classpath` union.
- **New MCP tools or parameters** — the whole point is that the existing
  `query`/`context` surface already has the right shape; only resolution
  coverage and honesty are missing.
- **Archive reading in the agent** — that complementary fallback lives in
  `../coding-agent-plan.md` (jar-aware `read`) and needs nothing from this
  plan.

## Sequencing and sizing

| Order | Item | Size | Risk |
|---|---|---|---|
| 1 | Status rows + empty-scope messaging | S | low — additive metadata; verify reuse rules |
| 2 | Description truthfulness | XS | none |
| 3a–3b | Build detection + classpath resolution | M | medium — subprocess + parsing; gated by kill switch and failure isolation |
| 3c | Multi-language extraction/collection | S–M | medium — cache-marker predicate consistency is the trap |
| 3d–3e | Tests + docs | M | — |

Items 1+2 ship first as one PR (they are valuable even if item 3 stalls, and
they define the messages item 3 later refines). Item 3 is one PR (3a–3e
together — a half-landed resolver that resolves but extracts nothing would
regress honesty).

Definition of done: `make test` green (release and debug), the
`tests/test_java_support.c` Maven suite untouched-and-green (byte-identical
existing Maven behavior), new fixtures under `tests/fixtures/` for the
Clojure paths, `-Wall -Wextra -Wpedantic -Werror` clean, and all
capability-describing strings (MCP descriptions, README, AGENTS.md) updated
in the same commits as the behavior they describe.
