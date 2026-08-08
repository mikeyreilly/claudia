#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
bin="${root}/build/code-lens"
runs=5
warmups=1
name="benchmark"
path=
expect_files=
expect_symbols=
expect_references=
expect_keywords=
expect_aliases=

usage() {
    cat <<EOF
Usage: scripts/benchmark-index.sh --path <repo-path> [options]

Options:
  --runs <n>                    Measured runs, default: 5
  --warmups <n>                 Warmup runs before measurements, default: 1
  --expect-files <n>            Fail if indexed file count differs
  --expect-symbols <n>          Fail if indexed symbol count differs
  --expect-references <n>       Fail if indexed reference count differs
  --expect-keywords <n>         Fail if indexed keyword count differs
  --expect-aliases <n>          Fail if indexed alias count differs
EOF
}

now_seconds() {
    perl -MTime::HiRes=time -e 'printf "%.6f\n", time'
}

die() {
    printf 'benchmark-index: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --path)
            [ "$#" -ge 2 ] || die "--path requires a value"
            path=$2
            shift 2
            ;;
        --runs)
            [ "$#" -ge 2 ] || die "--runs requires a value"
            runs=$2
            shift 2
            ;;
        --warmups)
            [ "$#" -ge 2 ] || die "--warmups requires a value"
            warmups=$2
            shift 2
            ;;
        --expect-files)
            [ "$#" -ge 2 ] || die "--expect-files requires a value"
            expect_files=$2
            shift 2
            ;;
        --expect-symbols)
            [ "$#" -ge 2 ] || die "--expect-symbols requires a value"
            expect_symbols=$2
            shift 2
            ;;
        --expect-references)
            [ "$#" -ge 2 ] || die "--expect-references requires a value"
            expect_references=$2
            shift 2
            ;;
        --expect-keywords)
            [ "$#" -ge 2 ] || die "--expect-keywords requires a value"
            expect_keywords=$2
            shift 2
            ;;
        --expect-aliases)
            [ "$#" -ge 2 ] || die "--expect-aliases requires a value"
            expect_aliases=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

[ -n "$path" ] || die "--path is required"
[ -x "$bin" ] || die "missing executable: $bin"
[ "$runs" -gt 0 ] 2>/dev/null || die "--runs must be a positive integer"
[ "$warmups" -ge 0 ] 2>/dev/null || die "--warmups must be a non-negative integer"

tmp="${TMPDIR:-/tmp}/code-lens-index-bench-$$"
times="${tmp}/times"
mkdir -p "$tmp"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

parse_counts() {
    sed -n 's/^indexed .*: \([0-9][0-9]*\) files, \([0-9][0-9]*\) symbols, \([0-9][0-9]*\) references, \([0-9][0-9]*\) keywords, \([0-9][0-9]*\) aliases.*$/\1 \2 \3 \4 \5/p'
}

readback_count() {
    readback_home=$1
    readback_repo=$2
    readback_table=$3

    CODE_LENS_HOME="$readback_home" "$bin" sql --repo "$readback_repo" \
        "SELECT COUNT(*) FROM ${readback_table}" | sed -n '2p'
}

verify_readback() {
    readback_home=$1
    readback_repo=$2
    cli_files=$3
    cli_symbols=$4
    cli_references=$5
    cli_keywords=$6
    cli_aliases=$7

    db_repos=$(readback_count "$readback_home" "$readback_repo" "Repo")
    db_files=$(readback_count "$readback_home" "$readback_repo" "File")
    db_symbols=$(readback_count "$readback_home" "$readback_repo" "Symbol")
    db_references=$(readback_count "$readback_home" "$readback_repo" "Ref")
    db_keywords=$(readback_count "$readback_home" "$readback_repo" "Keyword")
    db_aliases=$(readback_count "$readback_home" "$readback_repo" "Alias")

    [ "$db_repos" = "1" ] ||
        die "read-back Repo count mismatch: expected 1, database returned '${db_repos}'"
    [ "$db_files" = "$cli_files" ] ||
        die "read-back file count mismatch: indexer reported $cli_files, database returned '${db_files}'"
    [ "$db_symbols" = "$cli_symbols" ] ||
        die "read-back symbol count mismatch: indexer reported $cli_symbols, database returned '${db_symbols}'"
    [ "$db_references" = "$cli_references" ] ||
        die "read-back reference count mismatch: indexer reported $cli_references, database returned '${db_references}'"
    [ "$db_keywords" = "$cli_keywords" ] ||
        die "read-back keyword count mismatch: indexer reported $cli_keywords, database returned '${db_keywords}'"
    [ "$db_aliases" = "$cli_aliases" ] ||
        die "read-back alias count mismatch: indexer reported $cli_aliases, database returned '${db_aliases}'"
}

run_once() {
    run_kind=$1
    run_number=$2
    home="${tmp}/home-${run_kind}-${run_number}"
    mkdir -p "$home"

    start=$(now_seconds)
    output=$(CODE_LENS_HOME="$home" "$bin" index --repo "$path")
    end=$(now_seconds)
    elapsed=$(awk -v start="$start" -v end="$end" 'BEGIN { printf "%.6f", end - start }')
    counts=$(printf '%s\n' "$output" | parse_counts)
    [ -n "$counts" ] || die "could not parse index output: $output"

    set -- $counts
    files=$1
    symbols=$2
    references=$3
    keywords=$4
    aliases=$5

    [ -z "$expect_files" ] || [ "$files" = "$expect_files" ] ||
        die "file count mismatch: expected $expect_files, got $files"
    [ -z "$expect_symbols" ] || [ "$symbols" = "$expect_symbols" ] ||
        die "symbol count mismatch: expected $expect_symbols, got $symbols"
    [ -z "$expect_references" ] || [ "$references" = "$expect_references" ] ||
        die "reference count mismatch: expected $expect_references, got $references"
    [ -z "$expect_keywords" ] || [ "$keywords" = "$expect_keywords" ] ||
        die "keyword count mismatch: expected $expect_keywords, got $keywords"
    [ -z "$expect_aliases" ] || [ "$aliases" = "$expect_aliases" ] ||
        die "alias count mismatch: expected $expect_aliases, got $aliases"

    # Verify (outside the timed window) that the produced index actually
    # surfaces the same row counts the indexer reported.
    verify_readback "$home" "$path" \
        "$files" "$symbols" "$references" "$keywords" "$aliases"

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$run_kind" "$run_number" "$elapsed" "$files" "$symbols" "$references" "$keywords" "$aliases"
    if [ "$run_kind" = "run" ]; then
        printf '%s\n' "$elapsed" >> "$times"
    fi

    rm -rf "$home"
}

printf 'kind\trun\tseconds\tfiles\tsymbols\treferences\tkeywords\taliases\n'

i=1
while [ "$i" -le "$warmups" ]; do
    run_once "warmup" "$i"
    i=$((i + 1))
done

: > "$times"
i=1
while [ "$i" -le "$runs" ]; do
    run_once "run" "$i"
    i=$((i + 1))
done

sort -n "$times" | awk '
{
    values[NR] = $1
    sum += $1
}
END {
    if (NR == 0) {
        exit 1
    }
    if (NR % 2 == 1) {
        median = values[(NR + 1) / 2]
    } else {
        median = (values[NR / 2] + values[(NR / 2) + 1]) / 2
    }
    printf "summary\tmedian=%.6f\tmean=%.6f\tmin=%.6f\tmax=%.6f\n",
        median, sum / NR, values[1], values[NR]
}'
