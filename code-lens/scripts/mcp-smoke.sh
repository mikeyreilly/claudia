#!/bin/sh
# MCP smoke test: drives `code-lens mcp` over stdio with newline-delimited
# JSON-RPC 2.0 and asserts on semantic content only (symbol names, file
# paths, fixture counts). Fixture counts are part of the indexing
# correctness contract, so this test is intentionally insensitive to
# performance-oriented changes (schema layout, threading, batching, FTS
# configuration). Run it before committing indexer or MCP changes.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
bin="${CODE_LENS_BIN:-${root}/build/code-lens}"
fixture="${root}/tests/fixtures/mcp-smoke"

[ -x "$bin" ] || { printf 'mcp-smoke: missing executable: %s\n' "$bin" >&2; exit 1; }
[ -d "$fixture" ] || { printf 'mcp-smoke: missing fixture: %s\n' "$fixture" >&2; exit 1; }

tmp="${root}/build/mcp-smoke-$$"
worktree="${tmp}/worktree"
home="${tmp}/home"
out="${tmp}/responses"
mkdir "$tmp"
mkdir -p "$worktree" "$home"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cp -R "$fixture"/. "$worktree"
git -C "$worktree" init --quiet
git -C "$worktree" add --all
git -c user.name='code-lens smoke' \
    -c user.email='code-lens-smoke@example.invalid' \
    -C "$worktree" commit --quiet -m fixture

requests() {
    printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
    printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
    # Omitted repo uses the directory where the server was started.
    printf '%s\n' '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"query","arguments":{"query":"format-greeting"}}}'
    printf '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"context","arguments":{"repo":"%s","name":"format-greeting"}}}\n' "$worktree"
    # A descendant directory resolves to its containing worktree.
    printf '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"query","arguments":{"repo":"%s/src","query":"Greeter","kind":"class"}}}\n' "$worktree"
    printf '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"context","arguments":{"repo":"%s","name":"Greeter.greet"}}}\n' "$worktree"
    printf '{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"query","arguments":{"repo":"%s","query":"Counter","kind":"struct"}}}\n' "$worktree"
    printf '{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"context","arguments":{"repo":"%s","name":"counter_add"}}}\n' "$worktree"
    printf '{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"context","arguments":{"repo":"%s","name":"value"}}}\n' "$worktree"
    printf '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"sql","arguments":{"repo":"%s","query":"SELECT COUNT(*) FROM Symbol"}}}\n' "$worktree"
    # arguments serialized BEFORE name (legal JSON member order), with a
    # \uXXXX escape in the argument: dispatch must still pick params.name.
    printf '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"arguments":{"repo":"%s","name":"format-greeting","query":"\\u0066ormat-greeting"},"name":"query"}}\n' "$worktree"
}

# The perl alarm is a watchdog so a hung server fails the test instead of
# blocking a commit pipeline. Run the server inside the fixture to exercise
# the default repo path.
requests | (
    cd "$worktree"
    CODE_LENS_HOME="$home" \
        perl -e 'alarm 120; exec @ARGV or die "exec failed: $!"' -- "$bin" mcp
) > "$out"

failures=0

fail() {
    printf 'mcp-smoke: FAIL: %s\n' "$1" >&2
    failures=$((failures + 1))
}

response_line() {
    grep -F "\"id\":$1," "$out" || true
}

assert_response_contains() {
    assert_id=$1
    assert_needle=$2
    assert_label=$3
    line=$(response_line "$assert_id")
    if [ -z "$line" ]; then
        fail "no response for id $assert_id (${assert_label})"
        return
    fi
    case "$line" in
        *'"error"'*)
            fail "id $assert_id (${assert_label}) returned an error: $line"
            return
            ;;
    esac
    if ! printf '%s\n' "$line" | grep -qF -- "$assert_needle"; then
        fail "id $assert_id (${assert_label}) missing \"$assert_needle\": $line"
    fi
}

assert_response_contains 1 '"serverInfo":{"name":"code-lens"' "initialize"
assert_response_contains 1 '"instructions":"' "initialize instructions present"
for tool in query context sql; do
    assert_response_contains 2 "\"name\":\"$tool\"" "tools/list"
done
for hidden in list_repos remove_repo index stale cache; do
    if response_line 1 | grep -qiF "$hidden" || response_line 2 | grep -qiF "$hidden"; then
        fail "model-facing MCP metadata exposes $hidden"
    fi
done
assert_response_contains 2 '"required":["query"]' "repo is optional"
assert_response_contains 4 'format-greeting|function|smoke.util' "default-repo query"
assert_response_contains 4 'src/smoke/util.clj' "first query file path"
assert_response_contains 6 'format-greeting|function|smoke.util' "context definition"
assert_response_contains 6 'util/format-greeting -> smoke.util' "context alias-resolved reference"
assert_response_contains 6 'src/smoke/core.clj' "context reference site"
assert_response_contains 11 'Greeter|class|smoke.java.Greeter' "descendant-path Java class query"
assert_response_contains 12 'greet|method|smoke.java.Greeter' "Java context definition"
assert_response_contains 12 'greeter.greet -> smoke.java.Greeter' "Java resolved call site"
assert_response_contains 13 'Counter|struct|' "C struct query"
assert_response_contains 14 'counter_add|function|' "C context definition"
assert_response_contains 14 'src/smoke/c/c_app.c' "C external call site"
assert_response_contains 15 'counter->value -> Counter' "C member call site"
assert_response_contains 7 "repo: $worktree\\n" "sql repo disclosure"
assert_response_contains 7 'COUNT(*)\n16' "mixed-language sql count"
assert_response_contains 10 'format-greeting|function|smoke.util' "arguments-before-name dispatch"

if [ "$failures" -gt 0 ]; then
    printf 'mcp-smoke: %d assertion(s) failed\n' "$failures" >&2
    exit 1
fi

printf 'mcp-smoke: PASS (%s)\n' "$bin"
