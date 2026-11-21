#!/bin/sh
# One-time setup for the single Alpine Proxmox LXC. Idempotent: safe to rerun.
# Creates directories, service account, device CA, and one prod .env file.
# Does NOT start the stack; deploy with `git pull` + `docker compose up -d --build`.
set -eu

umask 077

die() {
    printf 'setup-alpine: %s\n' "$*" >&2
    exit 1
}

[ "$(id -u)" -eq 0 ] || die "must run as root"

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
INFRA_DIR=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
BASE=/opt/bethanys-bakery
SECRETS=$BASE/secrets
SERVICE_USER=bakery
SERVICE_GROUP=bakery

command -v apk >/dev/null 2>&1 || die "Alpine apk not found; this script targets an Alpine LXC"
command -v openssl >/dev/null 2>&1 || die "openssl is required"

apk add --no-cache docker docker-cli-compose git openssl curl jq

rc-update add docker boot >/dev/null 2>&1 || true
if ! rc-service docker status >/dev/null 2>&1; then
    rc-service docker start
fi

grep -q "^${SERVICE_GROUP}:" /etc/group || addgroup -S -g 10001 "$SERVICE_GROUP"
id -u "$SERVICE_USER" >/dev/null 2>&1 \
    || adduser -S -D -H -u 10001 -G "$SERVICE_GROUP" -s /sbin/nologin "$SERVICE_USER"

mkdir -p "$BASE" "$SECRETS"
chown -R "$SERVICE_USER:$SERVICE_GROUP" "$BASE"
chmod 750 "$BASE"
chmod 700 "$SECRETS"

# Device CA (also callable directly: infra/scripts/generate-ca.sh).
[ -f "$INFRA_DIR/scripts/generate-ca.sh" ] || die "missing $INFRA_DIR/scripts/generate-ca.sh"
sh "$INFRA_DIR/scripts/generate-ca.sh" "$SECRETS"
chown "$SERVICE_USER:$SERVICE_GROUP" "$SECRETS/device-ca.key" "$SECRETS/device-ca.crt"
chmod 600 "$SECRETS/device-ca.key" "$SECRETS/device-ca.crt"

# Replace a key in an env file without sourcing values (apostrophes are safe).
set_env() {
    _file=$1
    _key=$2
    _value=$3
    _tmp=$(mktemp)
    grep -v -E "^[[:space:]]*(export[[:space:]]+)?${_key}=" "$_file" >"$_tmp" || true
    printf '%s=%s\n' "$_key" "$_value" >>"$_tmp"
    cat "$_tmp" >"$_file"
    rm -f "$_tmp"
}

# Add a missing key only; never overwrite operator-supplied values on reruns.
ensure_env_key() {
    _file=$1
    _key=$2
    _value=$3
    if ! grep -q -E "^[[:space:]]*(export[[:space:]]+)?${_key}=" "$_file"; then
        printf '%s=%s\n' "$_key" "$_value" >>"$_file"
    fi
}

TARGET=$BASE/.env
if [ ! -f "$TARGET" ]; then
    cp "$INFRA_DIR/.env.example" "$TARGET"

    set_env "$TARGET" SPRING_PROFILES_ACTIVE prod
    set_env "$TARGET" DB_NAME bethanysbakery
    set_env "$TARGET" DB_USER bakery_prod
    set_env "$TARGET" DB_PASSWORD "$(openssl rand -hex 24)"
    set_env "$TARGET" JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
    set_env "$TARGET" APP_ENCRYPTION_KEY "$(openssl rand -base64 48 | tr -d '\n')"
    set_env "$TARGET" CLOUDFLARE_PROXY true
    set_env "$TARGET" CLOUDFLARE_TUNNEL_TOKEN REPLACE_ME_TUNNEL_TOKEN
    set_env "$TARGET" ADMIN_MTLS_LOCAL_BYPASS false
    set_env "$TARGET" APP_PUBLIC_BASE_URL https://bakery.cherry-home.com
    set_env "$TARGET" APP_ADMIN_API_URL https://bakery.cherry-home.com/admin/v1
    set_env "$TARGET" APP_PAIR_URL https://bakery.cherry-home.com/admin/v1/devices/pair
    set_env "$TARGET" ADMIN_APP_DOWNLOAD_URL \
        "https://downloads.cherry-home.com/bethanys-bakery-admin.apk"
    set_env "$TARGET" ADMIN_DEVICE_CA_CERT_PATH "$SECRETS/device-ca.crt"
    set_env "$TARGET" ADMIN_DEVICE_CA_KEY_PATH "$SECRETS/device-ca.key"
    set_env "$TARGET" API_IMAGE bethanys-bakery-api:prod
    set_env "$TARGET" WEB_IMAGE "${WEB_IMAGE:-httpd:2.4-alpine}"
    set_env "$TARGET" SPRINGDOC_API_DOCS_ENABLED false
    set_env "$TARGET" SPRINGDOC_SWAGGER_UI_ENABLED false
    printf 'setup-alpine: generated %s\n' "$TARGET"
else
    # Reruns add newly introduced keys but preserve operator-supplied values.
    ensure_env_key "$TARGET" CLOUDFLARE_PROXY true
    ensure_env_key "$TARGET" ADMIN_MTLS_LOCAL_BYPASS false
    ensure_env_key "$TARGET" CLOUDFLARE_TUNNEL_TOKEN REPLACE_ME_TUNNEL_TOKEN
    ensure_env_key "$TARGET" APP_PUBLIC_BASE_URL https://bakery.cherry-home.com
    ensure_env_key "$TARGET" APP_ADMIN_API_URL https://bakery.cherry-home.com/admin/v1
    ensure_env_key "$TARGET" APP_PAIR_URL https://bakery.cherry-home.com/admin/v1/devices/pair
    ensure_env_key "$TARGET" API_IMAGE bethanys-bakery-api:prod
    ensure_env_key "$TARGET" WEB_IMAGE "${WEB_IMAGE:-httpd:2.4-alpine}"
    ensure_env_key "$TARGET" CF_COUNTRY_HEADER CF-IPCountry
    ensure_env_key "$TARGET" CF_CONNECTING_IP_HEADER CF-Connecting-IP
    ensure_env_key "$TARGET" SPRINGDOC_API_DOCS_ENABLED false
    ensure_env_key "$TARGET" SPRINGDOC_SWAGGER_UI_ENABLED false
    printf 'setup-alpine: kept existing %s (only missing keys added)\n' "$TARGET"
fi

chown "$SERVICE_USER:$SERVICE_GROUP" "$TARGET"
chmod 600 "$TARGET"

printf '\nsetup-alpine: LXC prepared; stack NOT started.\n'
printf 'Next steps:\n'
printf '  1. Set CLOUDFLARE_TUNNEL_TOKEN, provider secrets, ADMIN_ALERT_EMAIL and\n'
printf '     ADMIN_ALERT_PHONE in %s (mode 600).\n' "$TARGET"
printf '  2. Upload %s to Cloudflare mTLS for the one public hostname.\n' "$SECRETS/device-ca.crt"
printf '  3. From the repo checkout: git pull && docker compose -f infra/compose.prod.yml \\\n'
printf '       --env-file %s up -d --build\n' "$TARGET"
printf '  4. Keep an off-repo copy of %s and the CA key; Proxmox full-container\n' "$TARGET"
printf '     backups are the only backup mechanism.\n'
