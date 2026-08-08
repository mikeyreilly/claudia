#!/bin/sh
set -eu

cc=${1:-cc}
tmp="${TMPDIR:-/tmp}/code-lens-c23-$$.c"
obj="${tmp}.o"

cleanup() {
    rm -f "$tmp" "$obj"
}
trap cleanup EXIT HUP INT TERM

printf 'int main(void) { return 0; }\n' > "$tmp"

if "$cc" -std=c23 -pedantic-errors -c "$tmp" -o "$obj" >/dev/null 2>&1; then
    printf '%s\n' '-std=c23'
elif "$cc" -std=c2x -pedantic-errors -c "$tmp" -o "$obj" >/dev/null 2>&1; then
    printf '%s\n' '-std=c2x'
else
    printf 'error: %s does not support ISO C23 or C2x mode\n' "$cc" >&2
    exit 1
fi
