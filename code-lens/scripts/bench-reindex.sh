#!/bin/sh
# Incremental re-index benchmark: copies the target repository's working
# tree (minus .git) into a scratch directory, primes a full index in an
# isolated CODE_LENS_HOME, then times two re-index flavours: the no-op
# up-to-date path, and the one-file-touch incremental path (a `;; touch N`
# comment appended to one .clj keeps the indexed counts stable while
# changing size/mtime). Reports the median wall and child-CPU seconds of
# each flavour.
set -eu

binary=build/code-lens
path=
iterations=8
cooldown=1

usage() {
    cat <<EOF
Usage: scripts/bench-reindex.sh --path <repo-path> [options]

Options:
  --binary <path>       code-lens binary, default: build/code-lens
  --iterations <n>      Timed runs per flavour, default: 8
  --cooldown <seconds>  Sleep between runs, default: 1
EOF
}

die() {
    printf 'bench-reindex: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --binary) [ "$#" -ge 2 ] || die "--binary requires a value"; binary=$2; shift 2 ;;
        --path) [ "$#" -ge 2 ] || die "--path requires a value"; path=$2; shift 2 ;;
        --iterations) [ "$#" -ge 2 ] || die "--iterations requires a value"; iterations=$2; shift 2 ;;
        --cooldown) [ "$#" -ge 2 ] || die "--cooldown requires a value"; cooldown=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

[ -n "$path" ] || die "--path is required"
[ -d "$path" ] || die "$path is not a directory"
[ -x "$binary" ] || die "$binary is not executable"
[ "$iterations" -gt 0 ] 2>/dev/null || die "--iterations must be a positive integer"

tmp="${TMPDIR:-/tmp}/code-lens-bench-reindex-$$"
mkdir -p "$tmp/repo" "$tmp/home"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# Working-tree copy without .git: the incremental machinery is identical
# either way, and the copy stays cheap for large checkouts.
(cd "$path" && tar cf - --exclude .git .) | (cd "$tmp/repo" && tar xf -)

touch_target=$(find "$tmp/repo" -name '*.clj' -type f | head -1)
[ -n "$touch_target" ] || die "no .clj files found under $path"

run_index() {
    run_label=$1
    output=$(CODE_LENS_HOME="$tmp/home" perl -MTime::HiRes=time -e '
        my $start = time;
        my $rc = system(@ARGV);
        my $wall = time - $start;
        exit(($rc >> 8) || 1) if $rc != 0;
        my (undef, undef, $cuser, $csys) = times;
        printf "__BENCH__ wall=%.6f cpu=%.6f\n", $wall, $cuser + $csys;
    ' "$binary" index --repo "$tmp/repo") || die "$run_label index run failed"
    printf '%s\n' "$output" | sed -n \
        's/^__BENCH__ wall=\([0-9.]*\) cpu=\([0-9.]*\)$/\1 \2/p'
}

median_report() {
    label=$1
    file=$2
    awk -v label="$label" '
        { wall[NR] = $1; cpu[NR] = $2 }
        END {
            n = NR
            for (i = 1; i <= n; i++)
                for (j = i + 1; j <= n; j++) {
                    if (wall[j] < wall[i]) { t = wall[i]; wall[i] = wall[j]; wall[j] = t }
                    if (cpu[j] < cpu[i]) { t = cpu[i]; cpu[i] = cpu[j]; cpu[j] = t }
                }
            wm = (n % 2 == 1) ? wall[(n + 1) / 2] : (wall[n / 2] + wall[n / 2 + 1]) / 2
            cm = (n % 2 == 1) ? cpu[(n + 1) / 2] : (cpu[n / 2] + cpu[n / 2 + 1]) / 2
            printf "%-18s wall median %.6fs cpu median %.6fs (n=%d)\n", label, wm, cm, n
        }' "$file"
}

printf 'priming full index...\n' >&2
prime=$(run_index prime)
printf 'full index: wall %ss cpu %ss\n' "${prime%% *}" "${prime##* }"

i=1
while [ "$i" -le "$iterations" ]; do
    run_index "no-op $i" >> "$tmp/noop"
    sleep "$cooldown"
    i=$((i + 1))
done
median_report "no-op re-index" "$tmp/noop"

i=1
while [ "$i" -le "$iterations" ]; do
    printf ';; touch %s\n' "$i" >> "$touch_target"
    run_index "touch $i" >> "$tmp/touch"
    sleep "$cooldown"
    i=$((i + 1))
done
median_report "one-file re-index" "$tmp/touch"
