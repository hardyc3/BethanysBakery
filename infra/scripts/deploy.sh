#!/bin/sh
# Validate env, build, start the stack, wait for health, print status.
# Schema migrations run at API startup (Flyway); no separate migration step.
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'deploy: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf 'Usage: %s prod|staging\n' "$0" >&2
}

[ $# -eq 1 ] || { usage; die "environment is required"; }
ENVIRONMENT=$1
case "$ENVIRONMENT" in
    prod|staging) ;;
    *) usage; die "unknown environment: $ENVIRONMENT" ;;
esac

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/$ENVIRONMENT/.env}
COMPOSE_FILE=$INFRA_DIR/compose.$ENVIRONMENT.yml
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE"
[ -f "$COMPOSE_FILE" ] || die "missing compose file: $COMPOSE_FILE"
export ENV_FILE

wait_healthy() {
    _service=$1
    _attempts=${2:-60}
    _i=1
    while [ "$_i" -le "$_attempts" ]; do
        _cid=$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q "$_service" | head -n 1)
        if [ -n "$_cid" ]; then
            _status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$_cid")
            if [ "$_status" = healthy ]; then
                printf 'deploy: %s healthy\n' "$_service"
                return 0
            fi
        else
            _status=missing
        fi
        printf 'deploy: waiting for %s (%s/%s): %s\n' "$_service" "$_i" "$_attempts" "$_status"
        sleep 5
        _i=$((_i + 1))
    done
    printf 'deploy: %s did not become healthy; recent logs:\n' "$_service" >&2
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs --tail 100 "$_service" >&2 || true
    return 1
}

printf 'deploy: validating %s configuration\n' "$ENVIRONMENT"
sh "$INFRA_DIR/scripts/check-env.sh" "$ENVIRONMENT"

printf 'deploy: building images\n'
sh "$INFRA_DIR/scripts/build.sh" "$ENVIRONMENT"

printf 'deploy: starting services (backup profile excluded)\n'
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --remove-orphans

for service in postgres api web cloudflared; do
    if ! wait_healthy "$service"; then
        die "$ENVIRONMENT deployment failed: $service unhealthy"
    fi
done

sh "$INFRA_DIR/scripts/status.sh" "$ENVIRONMENT"
printf 'deploy: %s complete; migrations ran at API startup\n' "$ENVIRONMENT"
