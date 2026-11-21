#!/bin/sh
# Show compose status plus health state for every production service.
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/.env}
COMPOSE_FILE=$INFRA_DIR/compose.prod.yml
[ -f "$ENV_FILE" ] || { printf 'status: missing env file: %s\n' "$ENV_FILE" >&2; exit 1; }
export ENV_FILE

printf 'status: production LXC\n'
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps

for service in postgres api web cloudflared; do
    cid=$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q "$service" | head -n 1)
    if [ -z "$cid" ]; then
        printf 'status: %s not running\n' "$service"
        continue
    fi
    health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid")
    state=$(docker inspect --format '{{.State.Status}}' "$cid")
    printf 'status: %-12s state=%-10s health=%s\n' "$service" "$state" "$health"
done
