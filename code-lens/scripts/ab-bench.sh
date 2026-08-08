#!/bin/sh
# A/B index benchmark: interleaves from-scratch index runs of two binaries
# (ABBA order per pair) so slow thermal drift on a laptop biases both
# binaries equally. Reports per-binary wall and child-CPU medians (wall is
# the decision metric, CPU is diagnostic), the median deltas, and two exact
# paired tests on wall times: the sign test and the more powerful Wilcoxon
# signed-rank test. Every run validates the indexed counts when --expect-*
# is given, so a change that alters indexing output fails loudly instead of
# "winning".
set -eu

bin_a=
bin_b=
path=
pairs=6
cooldown=2
expect_files=
expect_symbols=
expect_references=
expect_keywords=
expect_aliases=

usage() {
    cat <<EOF
Usage: scripts/ab-bench.sh --a <binary> --b <binary> --path <repo-path> [options]

Options:
  --pairs <n>                   Interleaved run pairs, default: 6
  --cooldown <seconds>          Sleep between runs, default: 2
  --expect-files <n>            Fail if indexed file count differs
  --expect-symbols <n>          Fail if indexed symbol count differs
  --expect-references <n>       Fail if indexed reference count differs
  --expect-keywords <n>         Fail if indexed keyword count differs
  --expect-aliases <n>          Fail if indexed alias count differs
EOF
}

die() {
    printf 'ab-bench: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --a) [ "$#" -ge 2 ] || die "--a requires a value"; bin_a=$2; shift 2 ;;
        --b) [ "$#" -ge 2 ] || die "--b requires a value"; bin_b=$2; shift 2 ;;
        --path) [ "$#" -ge 2 ] || die "--path requires a value"; path=$2; shift 2 ;;
        --pairs) [ "$#" -ge 2 ] || die "--pairs requires a value"; pairs=$2; shift 2 ;;
        --cooldown) [ "$#" -ge 2 ] || die "--cooldown requires a value"; cooldown=$2; shift 2 ;;
        --expect-files) [ "$#" -ge 2 ] || die "--expect-files requires a value"; expect_files=$2; shift 2 ;;
        --expect-symbols) [ "$#" -ge 2 ] || die "--expect-symbols requires a value"; expect_symbols=$2; shift 2 ;;
        --expect-references) [ "$#" -ge 2 ] || die "--expect-references requires a value"; expect_references=$2; shift 2 ;;
        --expect-keywords) [ "$#" -ge 2 ] || die "--expect-keywords requires a value"; expect_keywords=$2; shift 2 ;;
        --expect-aliases) [ "$#" -ge 2 ] || die "--expect-aliases requires a value"; expect_aliases=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

[ -n "$bin_a" ] && [ -x "$bin_a" ] || die "--a must name an executable"
[ -n "$bin_b" ] && [ -x "$bin_b" ] || die "--b must name an executable"
[ -n "$path" ] || die "--path is required"
[ "$pairs" -gt 0 ] 2>/dev/null || die "--pairs must be a positive integer"

tmp="${TMPDIR:-/tmp}/code-lens-ab-bench-$$"
mkdir -p "$tmp"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_once() {
    run_bin=$1
    run_label=$2
    run_number=$3
    home="${tmp}/home-${run_label}-${run_number}"
    mkdir -p "$home"

    # The perl wrapper measures the index run's wall time and the child's
    # user+sys CPU (times() reports reaped-child CPU), then appends its own
    # marker line after the binary's output.
    output=$(CODE_LENS_HOME="$home" perl -MTime::HiRes=time -e '
        my $start = time;
        my $rc = system(@ARGV);
        my $wall = time - $start;
        exit(($rc >> 8) || 1) if $rc != 0;
        my (undef, undef, $cuser, $csys) = times;
        printf "__AB_BENCH__ wall=%.6f cpu=%.6f\n", $wall, $cuser + $csys;
    ' "$run_bin" index --repo "$path") ||
        die "${run_label} run ${run_number}: index run failed"
    timings=$(printf '%s\n' "$output" | sed -n \
        's/^__AB_BENCH__ wall=\([0-9.]*\) cpu=\([0-9.]*\)$/\1 \2/p')
    [ -n "$timings" ] || die "could not parse timing line from ${run_bin}: $output"
    elapsed=${timings%% *}
    cpu=${timings##* }

    counts=$(printf '%s\n' "$output" | sed -n \
        's/^indexed .*: \([0-9][0-9]*\) files, \([0-9][0-9]*\) symbols, \([0-9][0-9]*\) references, \([0-9][0-9]*\) keywords, \([0-9][0-9]*\) aliases.*$/\1 \2 \3 \4 \5/p')
    [ -n "$counts" ] || die "could not parse index output from ${run_bin}: $output"

    set -- $counts
    [ -z "$expect_files" ] || [ "$1" = "$expect_files" ] ||
        die "${run_label} run ${run_number}: file count mismatch: expected $expect_files, got $1"
    [ -z "$expect_symbols" ] || [ "$2" = "$expect_symbols" ] ||
        die "${run_label} run ${run_number}: symbol count mismatch: expected $expect_symbols, got $2"
    [ -z "$expect_references" ] || [ "$3" = "$expect_references" ] ||
        die "${run_label} run ${run_number}: reference count mismatch: expected $expect_references, got $3"
    [ -z "$expect_keywords" ] || [ "$4" = "$expect_keywords" ] ||
        die "${run_label} run ${run_number}: keyword count mismatch: expected $expect_keywords, got $4"
    [ -z "$expect_aliases" ] || [ "$5" = "$expect_aliases" ] ||
        die "${run_label} run ${run_number}: alias count mismatch: expected $expect_aliases, got $5"

    printf 'run %s %s: wall %ss cpu %ss (%s files, %s symbols, %s references, %s keywords, %s aliases)\n' \
        "$run_label" "$run_number" "$elapsed" "$cpu" "$1" "$2" "$3" "$4" "$5" >&2
    printf '%s %s\n' "$elapsed" "$cpu" >> "${tmp}/times-${run_label}"

    rm -rf "$home"
    sleep "$cooldown"
}

# One untimed warmup per binary primes filesystem caches.
run_once "$bin_a" "warmup-a" 0 > /dev/null
rm -f "${tmp}/times-warmup-a"
run_once "$bin_b" "warmup-b" 0 > /dev/null
rm -f "${tmp}/times-warmup-b"

i=1
while [ "$i" -le "$pairs" ]; do
    if [ $((i % 2)) -eq 1 ]; then
        run_once "$bin_a" "a" "$i"
        run_once "$bin_b" "b" "$i"
    else
        run_once "$bin_b" "b" "$i"
        run_once "$bin_a" "a" "$i"
    fi
    i=$((i + 1))
done

stats() {
    awk -v col="$2" '{ print $col }' "$1" | sort -n | awk '
        { v[NR] = $1; sum += $1 }
        END {
            median = (NR % 2 == 1) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
            printf "median=%.6f mean=%.6f min=%.6f max=%.6f n=%d", median, sum / NR, v[1], v[NR], NR
        }'
}

median_of() {
    printf '%s' "$1" | sed 's/^median=\([0-9.]*\).*/\1/'
}

wall_a=$(stats "${tmp}/times-a" 1)
wall_b=$(stats "${tmp}/times-b" 1)
cpu_a=$(stats "${tmp}/times-a" 2)
cpu_b=$(stats "${tmp}/times-b" 2)

printf 'A %s:\n  wall %s\n  cpu  %s\n' "$bin_a" "$wall_a" "$cpu_a"
printf 'B %s:\n  wall %s\n  cpu  %s\n' "$bin_b" "$wall_b" "$cpu_b"
awk -v a="$(median_of "$wall_a")" -v b="$(median_of "$wall_b")" 'BEGIN {
    printf "B vs A wall median delta: %+.6fs (%+.2f%%) [decision metric]\n", b - a, (b - a) / a * 100.0
}'
awk -v a="$(median_of "$cpu_a")" -v b="$(median_of "$cpu_b")" 'BEGIN {
    printf "B vs A cpu median delta:  %+.6fs (%+.2f%%) [diagnostic]\n", b - a, (b - a) / a * 100.0
}'

# Exact two-sided sign test on per-pair wall times: line i of times-a and
# times-b belong to the same interleaved pair. Ties are dropped; under the
# null (no difference) B wins each decided pair with probability 1/2.
paste "${tmp}/times-a" "${tmp}/times-b" | awk '
    $3 < $1 { wins++; decided++ }
    $3 > $1 { decided++ }
    END {
        if (decided == 0) { print "sign test (wall): no decided pairs"; exit }
        p = 0
        kk = (wins < decided - wins) ? wins : decided - wins
        for (i = 0; i <= kk; i++) {
            lc = 0
            for (j = 1; j <= i; j++) lc += log(decided - i + j) - log(j)
            p += exp(lc + decided * log(0.5))
        }
        p *= 2
        if (p > 1) p = 1
        printf "sign test (wall): B won %d of %d decided pairs, exact two-sided p=%.4f\n", \
            wins, decided, p
    }'

# Exact two-sided Wilcoxon signed-rank test on the same pairs: unlike the
# sign test it also uses the magnitude ordering of the deltas, so it reaches
# significance with fewer pairs when the effect is consistent. Ranks of the
# absolute deltas are integers 1..n (microsecond timing makes true ties
# negligible; equal-magnitude deltas rank in input order), so the exact null
# distribution of the positive-rank sum comes from the classic subset-sum
# dynamic program over 2^n equally likely sign assignments.
paste "${tmp}/times-a" "${tmp}/times-b" | awk '
    $1 != $3 { d[++n] = $1 - $3 }
    END {
        if (n == 0) { print "wilcoxon (wall): no decided pairs"; exit }
        for (i = 1; i <= n; i++) {
            mag[i] = d[i] < 0 ? -d[i] : d[i]
            idx[i] = i
        }
        for (i = 1; i <= n; i++)
            for (j = i + 1; j <= n; j++)
                if (mag[idx[j]] < mag[idx[i]]) { t = idx[i]; idx[i] = idx[j]; idx[j] = t }
        wplus = 0
        for (r = 1; r <= n; r++)
            if (d[idx[r]] > 0) wplus += r
        maxw = n * (n + 1) / 2
        ways[0] = 1
        for (k = 1; k <= n; k++)
            for (w = maxw; w >= k; w--)
                ways[w] += ways[w - k]
        below = 0; above = 0
        for (w = 0; w <= maxw; w++) {
            if (w <= wplus) below += ways[w]
            if (w >= wplus) above += ways[w]
        }
        total = 2 ^ n
        p = 2 * ((below < above ? below : above) / total)
        if (p > 1) p = 1
        printf "wilcoxon (wall): B-faster rank sum %d of %d, exact two-sided p=%.4f\n", \
            wplus, maxw, p
    }'
