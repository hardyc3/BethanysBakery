#!/usr/bin/env bash
# Syntax check for the remaining shell scripts. No backup/env-check fixtures:
# those scripts were removed with the S8 ops simplification.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bb-script-tests.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

PASS=0
FAIL=0
SKIP=0

ok() { printf 'PASS %s\n' "$1"; PASS=$((PASS + 1)); }
skip() { printf 'SKIP %s: %s\n' "$1" "$2"; SKIP=$((SKIP + 1)); }

printf '== shell syntax ==\n'
mapfile -t SHELL_FILES < <(find "$ROOT/infra" -type f -name '*.sh' -print | sort)
if ((${#SHELL_FILES[@]} == 0)); then
  skip syntax "no .sh files under $ROOT/infra"
else
  for file in "${SHELL_FILES[@]}"; do
    if bash -n "$file" 2>"$TMP_DIR/bash-n.log"; then
      ok "bash -n ${file#"$ROOT"/}"
    else
      printf 'FAIL bash -n %s: %s\n' "${file#"$ROOT"/}" "$(tr '\n' ' ' <"$TMP_DIR/bash-n.log")" >&2
      FAIL=$((FAIL + 1))
    fi
  done
fi

printf '== optional shellcheck ==\n'
if ! command -v shellcheck >/dev/null 2>&1; then
  skip shellcheck "shellcheck not installed (bash -n is authoritative here)"
elif ((${#SHELL_FILES[@]} == 0)); then
  skip shellcheck "no shell files to check"
elif shellcheck -x "${SHELL_FILES[@]}"; then
  ok shellcheck
else
  printf 'WARN shellcheck: findings above are advisory for this harness\n'
fi

printf '\n== summary ==\n'
printf 'PASS=%d FAIL=%d SKIP=%d\n' "$PASS" "$FAIL" "$SKIP"
((FAIL == 0))
