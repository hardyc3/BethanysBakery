#!/usr/bin/env bash
# Defensive CLI harness for S7 shell scripts.
# - Always runs bash -n across infra/**/*.sh (including this file).
# - Runs fixture-driven negative cases for check-env.sh and backup.sh.
# - Missing scripts are SKIP, not FAIL, so this can run before the other agent's scripts land.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS_DIR="$ROOT/infra/scripts"
FIXTURES_DIR="$ROOT/infra/tests/fixtures"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bb-script-tests.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

PASS=0
FAIL=0
SKIP=0
LAST_LOG=""

ok() { printf 'PASS %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf 'FAIL %s: %s\n' "$1" "$2" >&2; FAIL=$((FAIL + 1)); }
skip() { printf 'SKIP %s: %s\n' "$1" "$2"; SKIP=$((SKIP + 1)); }

if command -v timeout >/dev/null 2>&1; then
  TIMEOUT=(timeout 30)
else
  TIMEOUT=()
fi

# Returns 0 when the command exits non-zero (expected for negative cases).
expect_nonzero() {
  local label="$1"
  shift
  LAST_LOG="$TMP_DIR/${label//[^A-Za-z0-9]/_}.log"
  if "${TIMEOUT[@]}" "$@" >"$LAST_LOG" 2>&1; then
    return 1
  fi
  return 0
}

show_last_log() {
  [[ -n "$LAST_LOG" && -f "$LAST_LOG" ]] || return 0
  printf '  last output: ' >&2
  head -n 5 "$LAST_LOG" | tr '\n' ' ' >&2
  printf '\n' >&2
}

printf '== shell syntax ==\n'
mapfile -t SHELL_FILES < <(find "$ROOT/infra" -type f -name '*.sh' -print | sort)
if ((${#SHELL_FILES[@]} == 0)); then
  skip syntax "no .sh files under $ROOT/infra"
else
  for file in "${SHELL_FILES[@]}"; do
    if bash -n "$file"; then
      ok "bash -n ${file#"$ROOT"/}"
    else
      bad "bash -n ${file#"$ROOT"/}" "syntax error"
    fi
  done
fi

printf '== optional shellcheck ==\n'
if ! command -v shellcheck >/dev/null 2>&1; then
  skip shellcheck "shellcheck not installed (bash -n is authoritative here)"
elif ((${#SHELL_FILES[@]} == 0)); then
  skip shellcheck "no shell files to check"
elif shellcheck -x "${SHELL_FILES[@]}"; then
  ok "shellcheck"
else
  printf 'WARN shellcheck: findings above are advisory for this harness\n'
fi

printf '== fixtures ==\n'
for fixture in check-env.missing.env check-env.weak.env backup.invalid.env; do
  if [[ -f "$FIXTURES_DIR/$fixture" ]]; then
    ok "fixture $fixture"
  else
    bad "fixture $fixture" "missing"
  fi
done

printf '== check-env.sh negative cases ==\n'
CHECK_ENV="$SCRIPTS_DIR/check-env.sh"
if [[ ! -f "$CHECK_ENV" ]]; then
  skip check-env "script not present yet: $CHECK_ENV"
else
  for fixture in check-env.missing.env check-env.weak.env; do
    path="$FIXTURES_DIR/$fixture"
    name="check-env/$fixture"
    # Scripts may accept a path argument, ENV_FILE/DOTENV_PATH, or exported vars.
    # Any of these interfaces is acceptable as long as invalid fixtures fail fast.
    if expect_nonzero "$name-arg" env ENV_FILE="$path" DOTENV_PATH="$path" bash "$CHECK_ENV" "$path"; then
      ok "$name"
    elif expect_nonzero "$name-env" env ENV_FILE="$path" DOTENV_PATH="$path" bash "$CHECK_ENV"; then
      ok "$name"
    elif expect_nonzero "$name-exported" env ENV_FILE="$path" DOTENV_PATH="$path" \
        DB_NAME=bethanysbakery DB_USER=bakery DB_PASSWORD=bakery JWT_SECRET=short \
        APP_ENCRYPTION_KEY=short ADMIN_MTLS_LOCAL_BYPASS=true bash "$CHECK_ENV"; then
      ok "$name"
    else
      bad "$name" "invalid fixture was accepted (or script unexpectedly succeeded)"
      show_last_log
    fi
  done
fi

printf '== backup.sh negative cases ==\n'
BACKUP="$SCRIPTS_DIR/backup.sh"
STUB_BIN="$TMP_DIR/stubbin"
mkdir -p "$STUB_BIN"
for cmd in docker restic pg_dump pg_restore psql; do
  printf '#!/bin/sh\necho "stub %s invoked" >&2\nexit 42\n' "$cmd" >"$STUB_BIN/$cmd"
  chmod 0755 "$STUB_BIN/$cmd"
done
STUBBED_PATH="$STUB_BIN:$PATH"

if [[ ! -f "$BACKUP" ]]; then
  skip backup "script not present yet: $BACKUP"
else
  invalid="$FIXTURES_DIR/backup.invalid.env"
  if expect_nonzero backup-invalid-arg env PATH="$STUBBED_PATH" ENV_FILE="$invalid" \
      DOTENV_PATH="$invalid" RESTIC_REPOSITORY= RESTIC_PASSWORD= DB_NAME= bash "$BACKUP" "$invalid"; then
    ok "backup/invalid-env"
  elif expect_nonzero backup-invalid-env env PATH="$STUBBED_PATH" ENV_FILE="$invalid" \
      DOTENV_PATH="$invalid" RESTIC_REPOSITORY= RESTIC_PASSWORD= DB_NAME= bash "$BACKUP"; then
    ok "backup/invalid-env"
  else
    bad "backup/invalid-env" "invalid backup fixture was accepted"
    show_last_log
  fi

  # Valid-looking values + stubbed external tools: failures must propagate non-zero.
  valid="$TMP_DIR/backup.stub.env"
  cat >"$valid" <<EOF
SPRING_PROFILES_ACTIVE=prod
DB_HOST=postgres
DB_PORT=5432
DB_NAME=bethanysbakery
DB_USER=bakery
DB_PASSWORD=fixture-only-not-a-real-password
RESTIC_REPOSITORY=$TMP_DIR/restic
RESTIC_PASSWORD=fixture-only-not-a-real-password
MEDIA_ROOT=/data/media
EOF
  if expect_nonzero backup-tool-failure env PATH="$STUBBED_PATH" ENV_FILE="$valid" \
      DOTENV_PATH="$valid" bash "$BACKUP" "$valid"; then
    ok "backup/tool-failure-propagation"
  elif expect_nonzero backup-tool-failure-env env PATH="$STUBBED_PATH" ENV_FILE="$valid" \
      DOTENV_PATH="$valid" bash "$BACKUP"; then
    ok "backup/tool-failure-propagation"
  else
    bad "backup/tool-failure-propagation" "backup ignored failed external tool"
    show_last_log
  fi
fi

printf '\n== summary ==\n'
printf 'PASS=%d FAIL=%d SKIP=%d\n' "$PASS" "$FAIL" "$SKIP"
((FAIL == 0))
