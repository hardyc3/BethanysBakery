#!/bin/sh
# Show compose status plus health state for every S7 service.
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'status: %s\n' "$*" >&2
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

printf 'status: %s\n' "$ENVIRONMENT"
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
