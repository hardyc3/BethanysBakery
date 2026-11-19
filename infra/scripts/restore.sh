#!/bin/sh
# Restore a restic snapshot. Requires explicit --yes.
# Usage: restore.sh [prod|staging] <snapshot> --yes
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'restore: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf 'Usage: %s [prod|staging] <snapshot> --yes\n' "$0" >&2
}

ENVIRONMENT=prod
if [ "$#" -gt 0 ]; then
    case $1 in
        prod|staging) ENVIRONMENT=$1; shift ;;
    esac
fi

SNAPSHOT=
CONFIRMED=no
while [ "$#" -gt 0 ]; do
    case $1 in
        --yes) CONFIRMED=yes ;;
        -h|--help) usage; exit 0 ;;
        *)
            [ -z "$SNAPSHOT" ] || { usage; die "unexpected argument: $1"; }
            SNAPSHOT=$1
            ;;
    esac
    shift
done

[ -n "$SNAPSHOT" ] || { usage; die "snapshot is required"; }
[ "$CONFIRMED" = yes ] || die "refusing to restore without --yes"

ENV_FILE=${ENV_FILE:-/opt/bethanys-bakery/$ENVIRONMENT/.env}
COMPOSE_FILE=$INFRA_DIR/compose.$ENVIRONMENT.yml
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE"
export ENV_FILE

command -v restic >/dev/null 2>&1 || die "restic is required on the host"

env_value() {
    _line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" "$ENV_FILE" | tail -n 1 || true)
    _value=$(printf '%s' "$_line" | sed -E "s/^[[:space:]]*(export[[:space:]]+)?$1=//")
    case $_value in
        \"*\") _value=${_value#\"}; _value=${_value%\"} ;;
        \'*\') _value=${_value#\'}; _value=${_value%\'} ;;
    esac
    printf '%s' "$_value"
}

db_name=$(env_value DB_NAME)
[ -n "$db_name" ] || db_name=bethanysbakery
db_user=$(env_value DB_USER)
[ -n "$db_user" ] || db_user=bakery
repo=$(env_value RESTIC_REPOSITORY)
[ -n "$repo" ] || die "RESTIC_REPOSITORY is missing from $ENV_FILE"
repo_password=$(env_value RESTIC_PASSWORD)
[ -n "$repo_password" ] || die "RESTIC_PASSWORD is missing from $ENV_FILE"

project=bethanys-bakery-$ENVIRONMENT
media_volume=${MEDIA_VOLUME:-$project-media-data}
workdir=$(mktemp -d "${TMPDIR:-/tmp}/bb-restore.$ENVIRONMENT.XXXXXX")
cleanup() {
    rm -rf "$workdir"
}
trap cleanup EXIT INT TERM

export RESTIC_REPOSITORY="$repo"
export RESTIC_PASSWORD="$repo_password"

printf 'restore: extracting snapshot %s\n' "$SNAPSHOT"
restic restore "$SNAPSHOT" --target "$workdir"

db_dump=$(find "$workdir" -type f -name 'db-*.dump' | head -n 1)
media_archive=$(find "$workdir" -type f -name 'media-*.tar.gz' | head -n 1)
[ -n "$db_dump" ] || die "snapshot has no pg_dump file"
[ -n "$media_archive" ] || die "snapshot has no media archive"

printf 'restore: stopping api and web\n'
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" stop api web

printf 'restore: restoring database %s\n' "$db_name"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
    pg_restore --clean --if-exists --exit-on-error -U "$db_user" -d "$db_name" <"$db_dump"

printf 'restore: replacing media volume contents\n'
media_name=$(basename "$media_archive")
docker run --rm \
    -e ARCHIVE="$media_name" \
    -v "$media_volume:/media" \
    -v "$workdir:/backup:ro" \
    "${MEDIA_UTIL_IMAGE:-alpine:3.20}" \
    sh -c "set -eu; find /media -mindepth 1 -maxdepth 1 -exec rm -rf {} +; tar -xzf \"/backup/\$ARCHIVE\" -C /media"

printf 'restore: starting api\n'
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --no-deps api

_wait_healthy() {
    _service=$1
    _attempts=60
    _i=1
    while [ "$_i" -le "$_attempts" ]; do
        _cid=$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q "$_service" | head -n 1)
        if [ -n "$_cid" ]; then
            _status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$_cid")
            if [ "$_status" = healthy ]; then
                printf 'restore: %s healthy\n' "$_service"
                return 0
            fi
        else
            _status=missing
        fi
        printf 'restore: waiting for %s (%s/%s): %s\n' "$_service" "$_i" "$_attempts" "$_status"
        sleep 5
        _i=$((_i + 1))
    done
    return 1
}

if ! _wait_healthy api; then
    printf 'restore: api failed health check; recent logs:\n' >&2
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs --tail 100 api >&2 || true
    die "$ENVIRONMENT restore failed: api unhealthy"
fi

printf 'restore: starting web\n'
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --no-deps web
sh "$INFRA_DIR/scripts/status.sh" "$ENVIRONMENT"
printf 'restore: OK snapshot=%s\n' "$SNAPSHOT"
