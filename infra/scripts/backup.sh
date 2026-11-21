#!/bin/sh
# Encrypted restic backup: pg_dump (custom format) + media tar, retention, verify.
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'backup: %s\n' "$*" >&2
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

command -v restic >/dev/null 2>&1 || die "restic is required on the host"
command -v jq >/dev/null 2>&1 || die "jq is required on the host"

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
workdir=$(mktemp -d "${TMPDIR:-/tmp}/bb-backup.$ENVIRONMENT.XXXXXX")
cleanup() {
    rm -rf "$workdir"
}
trap cleanup EXIT INT TERM

stamp=$(date -u +%Y%m%dT%H%M%SZ)
db_file=db-$ENVIRONMENT-$stamp.dump
media_file=media-$ENVIRONMENT-$stamp.tar.gz

printf 'backup: dumping database %s\n' "$db_name"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
    pg_dump -Fc -U "$db_user" -d "$db_name" >"$workdir/$db_file"
[ -s "$workdir/$db_file" ] || die "pg_dump produced an empty file"

printf 'backup: archiving media volume %s\n' "$media_volume"
docker run --rm \
    -v "$media_volume:/media:ro" \
    -v "$workdir:/backup" \
    "${MEDIA_UTIL_IMAGE:-alpine:3.20}" \
    tar -czf "/backup/$media_file" -C /media .
[ -s "$workdir/$media_file" ] || die "media tar is empty"

export RESTIC_REPOSITORY="$repo"
export RESTIC_PASSWORD="$repo_password"

if ! restic snapshots >/dev/null 2>&1; then
    printf 'backup: initializing restic repository %s\n' "$repo"
    restic init
fi

printf 'backup: uploading dump and media archive\n'
restic backup --tag "$ENVIRONMENT" --tag scheduled "$workdir/$db_file" "$workdir/$media_file"

printf 'backup: applying retention (7 daily, 4 weekly, 6 monthly)\n'
restic forget --keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune

printf 'backup: verifying repository and snapshot contents\n'
restic check
snapshot=$(restic snapshots --json --tag "$ENVIRONMENT" --latest 1 | jq -r '.[0].short_id')
[ -n "$snapshot" ] && [ "$snapshot" != null ] || die "could not resolve latest snapshot ID"
restic ls "$snapshot" >"$workdir/listing.txt"
grep -qF "$db_file" "$workdir/listing.txt" || die "snapshot is missing $db_file"
grep -qF "$media_file" "$workdir/listing.txt" || die "snapshot is missing $media_file"

printf 'backup: OK snapshot=%s repository=%s\n' "$snapshot" "$repo"
