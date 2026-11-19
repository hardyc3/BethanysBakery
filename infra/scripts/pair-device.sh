#!/bin/sh
# Issue a one-time device pairing code and print the CLI QR code.
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'pair-device: %s\n' "$*" >&2
    exit 1
}

ENVIRONMENT=${1:-prod}
case "$ENVIRONMENT" in
    prod|staging) ;;
    *) printf 'Usage: %s [prod|staging]\n' "$0" >&2; die "unknown environment: $ENVIRONMENT" ;;
esac

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/$ENVIRONMENT/.env}
COMPOSE_FILE=$INFRA_DIR/compose.$ENVIRONMENT.yml
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE"
export ENV_FILE

pg_cid=$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q postgres | head -n 1)
[ -n "$pg_cid" ] || die "postgres is not running; deploy $ENVIRONMENT first"

# PairDeviceCommand prints the code, URLs, and QR; no extra CLI flags required.
exec docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" \
    run --rm --no-deps api --pair-device
