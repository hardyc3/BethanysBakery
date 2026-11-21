#!/bin/sh
# S7 fresh-Alpine host setup. Idempotent: safe to run twice.
# Creates directories, service account, secrets, and per-environment .env files.
# Does NOT start the stack.
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

command -v apk >/dev/null 2>&1 || die "Alpine apk not found; this script targets Alpine Linux"
command -v openssl >/dev/null 2>&1 || die "openssl is required"

apk add --no-cache docker docker-cli-compose restic openssl curl jq tar gzip

rc-update add docker boot >/dev/null 2>&1 || true
if ! rc-service docker status >/dev/null 2>&1; then
    rc-service docker start
fi

grep -q "^${SERVICE_GROUP}:" /etc/group || addgroup -S -g 10001 "$SERVICE_GROUP"
id -u "$SERVICE_USER" >/dev/null 2>&1 \
    || adduser -S -D -H -u 10001 -G "$SERVICE_GROUP" -s /sbin/nologin "$SERVICE_USER"

mkdir -p "$BASE/prod" "$BASE/staging" "$SECRETS" "$BASE/backups" "$BASE/media"
chown -R "$SERVICE_USER:$SERVICE_GROUP" "$BASE"
chmod 750 "$BASE" "$BASE/prod" "$BASE/staging" "$BASE/media"
chmod 700 "$SECRETS" "$BASE/backups"

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

generate_env() {
    _env=$1
    _target=$BASE/$_env/.env
    _created=no
    if [ ! -f "$_target" ]; then
        cp "$INFRA_DIR/.env.example" "$_target"
        _created=yes
    fi

    if [ "$_created" = yes ]; then
        case $_env in
            prod)
                _db_name=bethanysbakery
                _db_user=bakery_prod
                _public=https://bakery.cherry-home.com
                _admin=https://bakery-admin-api.cherry-home.com
                _pair=https://bakery-pair.cherry-home.com/pair
                ;;
            staging)
                _db_name=bethanysbakery_staging
                _db_user=bakery_staging
                _public=https://staging-bakery.cherry-home.com
                _admin=https://staging-bakery-admin-api.cherry-home.com
                _pair=https://staging-bakery-pair.cherry-home.com/pair
                ;;
            *) die "unsupported environment: $_env" ;;
        esac

        _db_password=$(openssl rand -hex 24)
        _jwt_secret=$(openssl rand -base64 48 | tr -d '\n')
        _encryption_key=$(openssl rand -base64 48 | tr -d '\n')
        _restic_password=$(openssl rand -hex 32)

        set_env "$_target" SPRING_PROFILES_ACTIVE "$_env"
        set_env "$_target" DB_NAME "$_db_name"
        set_env "$_target" DB_USER "$_db_user"
        set_env "$_target" DB_PASSWORD "$_db_password"
        set_env "$_target" JWT_SECRET "$_jwt_secret"
        set_env "$_target" APP_ENCRYPTION_KEY "$_encryption_key"
        set_env "$_target" RESTIC_PASSWORD "$_restic_password"
        set_env "$_target" RESTIC_REPOSITORY "$BASE/backups/restic/$_env"
        set_env "$_target" CLOUDFLARE_PROXY true
        set_env "$_target" ADMIN_MTLS_LOCAL_BYPASS false
        set_env "$_target" APP_PUBLIC_BASE_URL "$_public"
        set_env "$_target" APP_ADMIN_API_URL "$_admin"
        set_env "$_target" APP_PAIR_URL "$_pair"
        set_env "$_target" ADMIN_APP_DOWNLOAD_URL \
            "https://downloads.cherry-home.com/bethanys-bakery-admin.apk"
        set_env "$_target" ADMIN_DEVICE_CA_CERT_PATH "$SECRETS/device-ca.crt"
        set_env "$_target" ADMIN_DEVICE_CA_KEY_PATH "$SECRETS/device-ca.key"
        set_env "$_target" CLOUDFLARE_TUNNEL_TOKEN REPLACE_ME_PROD_TUNNEL_TOKEN
        set_env "$_target" CLOUDFLARE_TUNNEL_TOKEN_STAGING REPLACE_ME_STAGING_TUNNEL_TOKEN
        set_env "$_target" API_IMAGE "bethanys-bakery-api:$_env"
        set_env "$_target" WEB_IMAGE "${WEB_IMAGE:-httpd:2.4-alpine}"
        set_env "$_target" SPRINGDOC_API_DOCS_ENABLED false
        set_env "$_target" SPRINGDOC_SWAGGER_UI_ENABLED false
        printf 'setup-alpine: generated %s (mode 600)\n' "$_target"
    else
        # Reruns add newly introduced keys but preserve existing values.
        ensure_env_key "$_target" CLOUDFLARE_PROXY true
        ensure_env_key "$_target" ADMIN_MTLS_LOCAL_BYPASS false
        ensure_env_key "$_target" CLOUDFLARE_TUNNEL_TOKEN REPLACE_ME_PROD_TUNNEL_TOKEN
        ensure_env_key "$_target" CLOUDFLARE_TUNNEL_TOKEN_STAGING REPLACE_ME_STAGING_TUNNEL_TOKEN
        ensure_env_key "$_target" API_IMAGE "bethanys-bakery-api:$_env"
        ensure_env_key "$_target" WEB_IMAGE "${WEB_IMAGE:-httpd:2.4-alpine}"
        ensure_env_key "$_target" RESTIC_REPOSITORY "$BASE/backups/restic/$_env"
        ensure_env_key "$_target" CF_COUNTRY_HEADER CF-IPCountry
        ensure_env_key "$_target" CF_CONNECTING_IP_HEADER CF-Connecting-IP
        ensure_env_key "$_target" SPRINGDOC_API_DOCS_ENABLED false
        ensure_env_key "$_target" SPRINGDOC_SWAGGER_UI_ENABLED false
        printf 'setup-alpine: kept existing %s (only missing keys added)\n' "$_target"
    fi

    chown "$SERVICE_USER:$SERVICE_GROUP" "$_target"
    chmod 600 "$_target"
}

generate_env prod
generate_env staging

printf '\nsetup-alpine: host prepared; stack NOT started.\n'
printf 'Next steps:\n'
printf '  1. Set CLOUDFLARE_TUNNEL_TOKEN in %s/prod/.env\n' "$BASE/prod"
printf '     and CLOUDFLARE_TUNNEL_TOKEN_STAGING in %s/staging/.env\n' "$BASE/staging"
printf '  2. Upload %s to Cloudflare mTLS for the admin API hostnames.\n' "$SECRETS/device-ca.crt"
printf '  3. Fill provider secrets (Mailgun/Textbee/FCM) in each .env.\n'
printf '  4. Run: %s/scripts/check-env.sh prod\n' "$INFRA_DIR"
printf '  5. Run: %s/scripts/deploy.sh prod\n' "$INFRA_DIR"
