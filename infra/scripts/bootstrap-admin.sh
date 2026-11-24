#!/bin/sh
# Bootstrap the first admin via the API CLI inside the running stack.
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'bootstrap-admin: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf 'Usage: ADMIN_BOOTSTRAP_PASSWORD=<12+ chars> %s <email> <name>\n' "$0" >&2
}

[ "$#" -eq 2 ] || { usage; die "email and name are required"; }
email=$1
name=$2

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/.env}
COMPOSE_FILE=$INFRA_DIR/compose.prod.yml
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE"
export ENV_FILE

pg_cid=$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q postgres | head -n 1)
[ -n "$pg_cid" ] || die "postgres is not running; deploy the LXC stack first"

if [ -n "${ADMIN_BOOTSTRAP_PASSWORD:-}" ]; then
    [ "${#ADMIN_BOOTSTRAP_PASSWORD}" -ge 12 ] \
        || die "ADMIN_BOOTSTRAP_PASSWORD must be at least 12 characters"
    exec docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" \
        run --rm --no-deps -e ADMIN_BOOTSTRAP_PASSWORD api \
        --bootstrap-admin --email "$email" --name "$name"
fi

printf 'bootstrap-admin: ADMIN_BOOTSTRAP_PASSWORD unset; opening interactive prompt\n' >&2
exec docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" \
    run --rm --no-deps -it api \
    --bootstrap-admin --email "$email" --name "$name"
