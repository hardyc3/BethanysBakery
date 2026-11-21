#!/bin/sh
# Tail production compose logs. Defaults: all services, last 200 lines, follow.
# Examples: logs.sh api; LOG_LINES=50 logs.sh postgres api
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'logs: %s\n' "$*" >&2
    exit 1
}

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/.env}
COMPOSE_FILE=$INFRA_DIR/compose.prod.yml
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE"
export ENV_FILE

if [ "$#" -eq 0 ]; then
    set -- postgres api web cloudflared
fi

lines=${LOG_LINES:-200}
case $lines in
    ''|*[!0-9]*) die "LOG_LINES must be a positive integer" ;;
esac

# Follow only on a terminal; piped use (grep/tail in runbooks) must terminate.
if [ -t 1 ]; then
    exec docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs --tail "$lines" -f "$@"
fi
exec docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs --tail "$lines" "$@"
