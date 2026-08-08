#!/bin/sh
# One-command per-change gate: builds the working tree, runs the test suite
# and the MCP smoke, verifies index-content equivalence against a baseline
# binary (dump hash minus the timestamped Repo row), and A/B-benchmarks the
# fresh build against the baseline with counts pinned to what the baseline
# produces. Wall regressions do not fail the gate (that judgment call stays
# human); every correctness check does.
set -eu

baseline=
path=
pairs=8
skip_bench=0

usage() {
    cat <<EOF
Usage: scripts/gate.sh --baseline <binary> --path <repo-path> [options]

Options:
  --pairs <n>     ab-bench pairs, default: 8
  --skip-bench    Correctness checks only (no A/B timing)
EOF
}

die() {
    printf 'gate: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --baseline) [ "$#" -ge 2 ] || die "--baseline requires a value"; baseline=$2; shift 2 ;;
        --path) [ "$#" -ge 2 ] || die "--path requires a value"; path=$2; shift 2 ;;
        --pairs) [ "$#" -ge 2 ] || die "--pairs requires a value"; pairs=$2; shift 2 ;;
        --skip-bench) skip_bench=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

[ -n "$baseline" ] && [ -x "$baseline" ] || die "--baseline must name an executable"
[ -n "$path" ] && [ -d "$path" ] || die "--path must name a directory"

tmp="${TMPDIR:-/tmp}/code-lens-gate-$$"
mkdir -p "$tmp"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

step() {
    printf '=== gate: %s\n' "$*" >&2
}

# The pattern rules cover header changes, but a fast git checkout can still
# leave a same-second-stale object (make 3.81 mtime granularity).
step "build (forcing app object rebuild)"
rm -f build/release/obj/code_lens.o
make release >/dev/null

step "make test"
make test >/dev/null 2>&1 || die "make test failed"

step "mcp-smoke"
scripts/mcp-smoke.sh >/dev/null 2>&1 || die "mcp-smoke failed"

index_and_hash() {
    run_bin=$1
    run_home=$2
    mkdir -p "$run_home"
    CODE_LENS_HOME="$run_home" "$run_bin" index --repo "$path" > "${run_home}/out.txt" 2>/dev/null ||
        die "index run failed for $run_bin"
    db=$(find "$run_home/repos" -name index.sqlite | head -1)
    [ -n "$db" ] || die "no index.sqlite produced by $run_bin"
    sqlite3 "$db" .dump | grep -v 'INSERT INTO Repo' | shasum -a 256 | cut -d' ' -f1
}

step "content equivalence vs baseline"
hash_base=$(index_and_hash "$baseline" "$tmp/home-base")
hash_new=$(index_and_hash build/code-lens "$tmp/home-new")
if [ "$hash_base" != "$hash_new" ]; then
    printf 'gate: WARNING: index content differs from baseline\n' >&2
    printf '  baseline %s\n  new      %s\n' "$hash_base" "$hash_new" >&2
    printf 'gate: (expected only for deliberate schema/format changes)\n' >&2
else
    printf 'content hash: %.16s (identical)\n' "$hash_new"
fi

counts=$(sed -n \
    's/^indexed .*: \([0-9]*\) files, \([0-9]*\) symbols, \([0-9]*\) references, \([0-9]*\) keywords, \([0-9]*\) aliases.*$/\1 \2 \3 \4 \5/p' \
    "$tmp/home-base/out.txt")
[ -n "$counts" ] || die "could not parse baseline counts"
set -- $counts
printf 'baseline counts: %s files, %s symbols, %s references, %s keywords, %s aliases\n' \
    "$1" "$2" "$3" "$4" "$5"

if [ "$skip_bench" = "1" ]; then
    step "PASS (bench skipped)"
    exit 0
fi

step "ab-bench baseline vs build/code-lens ($pairs pairs)"
scripts/ab-bench.sh --a "$baseline" --b build/code-lens --path "$path" --pairs "$pairs" \
    --expect-files "$1" --expect-symbols "$2" --expect-references "$3" \
    --expect-keywords "$4" --expect-aliases "$5" 2>/dev/null

step "PASS"
