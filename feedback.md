Honest answer: the harness held up well overall — two repos, GitHub, and Jira in one session with no capability gaps — but there were a handful of concrete friction points. Ranked roughly by impact:

## Real friction I hit

**1. Tilde expansion is inconsistent across tools.**
My very first `read` call failed: `read ~/wa/taxamo-tax-id-checker/...` resolved to `/Users/Michael.Reilly/wa/taxamo-apparatus/~/wa/...` — the `~` was treated as a literal relative path against the CWD. The same `~` works fine in `shell`. That asymmetry is a trap because path strings that work in one tool silently mean something different in another. (Credit where due: the error message echoed the resolved path, so diagnosis was instant.) Suggestion: expand `~` in `read`/`edit`/`grep`/`find`/`ls`, or reject it with an explicit "tilde is not expanded" note.

**2. `grep` doesn't respect `.gitignore`.**
Searching apparatus for `use-data-platform` returned a wall of truncated JSON from `logs/marketplace-api.test.log` — almost certainly gitignored, definitely irrelevant, and it burned a chunk of my 50KB output budget. `.git` and `node_modules` are skipped, but ripgrep-style gitignore awareness (or an `excludeGlob` param) would have made that call clean. This was partly self-inflicted (I omitted a `glob` filter), but gitignore-by-default fixes the root cause.

**3. Silent zero-matches with a subtly wrong glob.**
`grep` with `glob: "src/**/*.clj"` against the apparatus root returned "No matches found" for `validate-tax-number-async` — because the sources live under `bases/marketplace-api/src/`, not top-level `src/`. My glob was wrong, but the output couldn't tell me *why*: "0 files matched the glob" vs. "files matched, no content hits" are very different failures. Distinguishing them in the response would have saved a fallback to `shell grep -rn`.

**4. Dependency source spelunking is manual.**
The single most consequential lookup of the review — confirming that `baselayer.validation/build-validator` builds *closed* schemas, which is what makes the deploy-ordering issue blocking rather than theoretical — required finding the jar in `~/.m2`, `unzip`-ing it to `/tmp`, and grepping the extracted file. The code-lens tools advertise dependency-scope search, and honestly I should have tried `code-lens_query` with `scope: dependencies` before hand-rolling it; habit steered me to `grep`. So this is half a harness suggestion (make "show me the source of dependency X" a one-step operation) and half a note that the semantic tools may be underused when the generic ones are *almost* good enough.

**5. Verbose defaults on the Jira/GitHub side.**
The default `getJiraIssue` payload is dominated by avatar URLs in four sizes and `self` links. I learned to pass `fields` on later calls, but a trimmed default (opt-in for the full object) would fit this kind of work better. Same mild note for the Snyk bot comments in `get_comments` — expected, just noisy.

## What worked well

- **Local checkouts + `shell` is the right review setup.** Being able to run `git diff origin/PUPP-3450...HEAD` to verify what the stacked PR actually adds over its base, and `git log` to confirm branch state, beat anything the GitHub API offers.
- **`get_diff` in one call** for each PR, with full hunks, was exactly right.
- **Parallel batching of independent calls** (ticket + both PRs in one block) noticeably shortened the investigation.
- Output bounds were sane everywhere except the gitignore case above.

## One workflow-level suggestion

For cross-repo review tasks specifically, the expensive part was stitching *referenced-but-unchanged* code: every diff hunk sent me to `read` the surrounding file, then to definitions in other namespaces (`resolve-settings`, `determine-merchant`, the mount lifecycle). A "diff with resolvable context" affordance — or just me leaning harder on `code-lens_context`, which resolves Clojure aliases and call sites and was sitting right there — would compress the longest phase of this session. The clj-kondo/midje false positives at the end were repo-config noise, not harness fault.

None of this blocked the review; the tilde and gitignore items are the only two I'd call actual defects, and both have cheap fixes.