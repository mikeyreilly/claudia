#!/bin/sh
# Training run for the PGO build (invoked by `make pgo`).
# $1 = instrumented code-lens binary, $2 = repo path to index for training.
# Exercises the hot paths that dominate indexing (walk, parse, emit, FTS)
# plus the query/context readers, in an isolated CODE_LENS_HOME.
set -eu

BIN="$1"
TRAIN_PATH="$2"

HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/code-lens-pgo.XXXXXX")"
trap 'rm -rf "$HOME_DIR"' EXIT

export CODE_LENS_HOME="$HOME_DIR"

"$BIN" index --repo "$TRAIN_PATH" >/dev/null
"$BIN" query --repo "$TRAIN_PATH" 'greet' >/dev/null || true
"$BIN" context --repo "$TRAIN_PATH" --name greet-user >/dev/null || true
"$BIN" sql --repo "$TRAIN_PATH" 'SELECT count(*) FROM Symbol' >/dev/null
"$BIN" remove --repo "$TRAIN_PATH" >/dev/null

echo "pgo-train: profile data written"
