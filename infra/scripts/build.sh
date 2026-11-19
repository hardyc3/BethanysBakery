#!/bin/sh
# Build the API image and prepare the web image (placeholder until W8).
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$INFRA_DIR/.." && pwd)

die() {
    printf 'build: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf 'Usage: %s [prod|staging]\n' "$0" >&2
}

ENVIRONMENT=${1:-prod}
case "$ENVIRONMENT" in
    prod|staging) ;;
    *) usage; die "unknown environment: $ENVIRONMENT" ;;
esac

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/$ENVIRONMENT/.env}
COMPOSE_FILE=$INFRA_DIR/compose.$ENVIRONMENT.yml
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE"
[ -f "$COMPOSE_FILE" ] || die "missing compose file: $COMPOSE_FILE"
export ENV_FILE

env_value() {
    _line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" "$ENV_FILE" | tail -n 1 || true)
    _value=$(printf '%s' "$_line" | sed -E "s/^[[:space:]]*(export[[:space:]]+)?$1=//")
    case $_value in
        \"*\") _value=${_value#\"}; _value=${_value%\"} ;;
        \'*\') _value=${_value#\'}; _value=${_value%\'} ;;
    esac
    printf '%s' "$_value"
}

api_image=$(env_value API_IMAGE)
[ -n "$api_image" ] || api_image="bethanys-bakery-api:$ENVIRONMENT"
web_image=$(env_value WEB_IMAGE)
[ -n "$web_image" ] || web_image=httpd:2.4-alpine

printf 'build: API image %s from %s/server/Dockerfile\n' "$api_image" "$REPO_ROOT"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" build api

if [ -f "$REPO_ROOT/web/Dockerfile" ]; then
    printf 'build: W8 web image detected, building %s\n' "$web_image"
    docker build -t "$web_image" "$REPO_ROOT/web"
elif ! docker image inspect "$web_image" >/dev/null 2>&1; then
    printf 'build: W8 absent; pulling placeholder web image %s\n' "$web_image"
    docker pull "$web_image"
else
    printf 'build: using existing placeholder web image %s\n' "$web_image"
fi

printf 'build: %s images ready\n' "$ENVIRONMENT"
