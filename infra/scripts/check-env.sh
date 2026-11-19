#!/bin/sh
# Fail fast on missing or weak prod/staging deployment configuration.
set -eu

die() {
    printf 'check-env: %s\n' "$*" >&2
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
[ -f "$ENV_FILE" ] || die "missing env file: $ENV_FILE (set ENV_FILE to override)"
[ -r "$ENV_FILE" ] || die "env file not readable: $ENV_FILE"

env_value() {
    _key=$1
    _file=$2
    _line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?${_key}=" "$_file" | tail -n 1 || true)
    _value=$(printf '%s' "$_line" | sed -E "s/^[[:space:]]*(export[[:space:]]+)?${_key}=//")
    case $_value in
        \"*\") _value=${_value#\"}; _value=${_value%\"} ;;
        \'*\') _value=${_value#\'}; _value=${_value%\'} ;;
    esac
    printf '%s' "$_value"
}

require_value() {
    _key=$1
    _value=$(env_value "$_key" "$ENV_FILE")
    [ -n "$_value" ] || die "$_key is missing or blank in $ENV_FILE"
    printf '%s' "$_value"
}

require_https() {
    _key=$1
    _value=$(require_value "$_key")
    case $_value in
        https://*) ;;
        *) die "$_key must use HTTPS (got: $_value)" ;;
    esac
    case $_value in
        *example.com*) die "$_key must not use the example.com placeholder" ;;
    esac
}

require_strong_secret() {
    _key=$1
    _value=$(require_value "$_key")
    [ "${#_value}" -ge 32 ] || die "$_key must be at least 32 bytes"
    case $_value in
        *[[:space:]]*) die "$_key must not contain whitespace" ;;
    esac
    _lower=$(printf '%s' "$_value" | tr '[:upper:]' '[:lower:]')
    for _weak in change-me changeme secret test dev example password postgres bakery; do
        if [ "$_lower" = "$_weak" ]; then
            die "$_key is a known weak value"
        fi
    done
}

[ "$(require_value CLOUDFLARE_PROXY)" = true ] \
    || die "CLOUDFLARE_PROXY must be true under $ENVIRONMENT"

_profile=$(require_value SPRING_PROFILES_ACTIVE)
[ "$_profile" = "$ENVIRONMENT" ] || die "SPRING_PROFILES_ACTIVE must be $ENVIRONMENT (got: $_profile)"

[ "$(require_value ADMIN_MTLS_LOCAL_BYPASS)" = false ] \
    || die "ADMIN_MTLS_LOCAL_BYPASS must be false under $ENVIRONMENT"

_db_user=$(require_value DB_USER)
case $_db_user in
    bakery|postgres|admin|test|dev) die "DB_USER is a local/dev default" ;;
esac
_db_password=$(require_value DB_PASSWORD)
[ "${#_db_password}" -ge 16 ] || die "DB_PASSWORD must be at least 16 characters"
_db_lower=$(printf '%s' "$_db_password" | tr '[:upper:]' '[:lower:]')
for _weak in change-me changeme bakery password postgres secret test dev example; do
    if [ "$_db_lower" = "$_weak" ]; then
        die "DB_PASSWORD is a known local/dev default"
    fi
done

require_strong_secret JWT_SECRET
require_strong_secret APP_ENCRYPTION_KEY

_ca_cert=$(require_value ADMIN_DEVICE_CA_CERT_PATH)
_ca_key=$(require_value ADMIN_DEVICE_CA_KEY_PATH)
[ -f "$_ca_cert" ] || die "ADMIN_DEVICE_CA_CERT_PATH does not point to a file: $_ca_cert"
[ -f "$_ca_key" ] || die "ADMIN_DEVICE_CA_KEY_PATH does not point to a file: $_ca_key"
[ -r "$_ca_cert" ] || die "CA certificate is not readable: $_ca_cert"
[ -r "$_ca_key" ] || die "CA private key is not readable: $_ca_key"

require_https APP_PUBLIC_BASE_URL
require_https APP_ADMIN_API_URL
require_https APP_PAIR_URL
require_https ADMIN_APP_DOWNLOAD_URL

require_value ADMIN_MTLS_HEADER >/dev/null
require_value ADMIN_MTLS_VERIFIED_HEADER >/dev/null


for _url_key in MAILGUN_API_BASE TEXTBEE_BASE_URL; do
    _url=$(env_value "$_url_key" "$ENV_FILE")
    if [ -n "$_url" ]; then
        case $_url in
            https://*) ;;
            *) die "$_url_key must use HTTPS outside local/test" ;;
        esac
    fi
done

if [ "$ENVIRONMENT" = staging ]; then
    require_value CLOUDFLARE_TUNNEL_TOKEN_STAGING >/dev/null
    case $(require_value CLOUDFLARE_TUNNEL_TOKEN_STAGING) in
        REPLACE_ME*|CHANGE_ME*) die "CLOUDFLARE_TUNNEL_TOKEN_STAGING is a placeholder" ;;
    esac
else
    require_value CLOUDFLARE_TUNNEL_TOKEN >/dev/null
    case $(require_value CLOUDFLARE_TUNNEL_TOKEN) in
        REPLACE_ME*|CHANGE_ME*) die "CLOUDFLARE_TUNNEL_TOKEN is a placeholder" ;;
    esac
fi

require_value RESTIC_REPOSITORY >/dev/null
require_strong_secret RESTIC_PASSWORD

for _flag in SPRINGDOC_API_DOCS_ENABLED SPRINGDOC_SWAGGER_UI_ENABLED; do
    _flag_value=$(env_value "$_flag" "$ENV_FILE")
    [ "$_flag_value" != true ] || die "$_flag must be false under $ENVIRONMENT"
done

_otp_sender=$(env_value OTP_SENDER "$ENV_FILE")
[ "$_otp_sender" != local ] || die "OTP_SENDER=local is not allowed under $ENVIRONMENT"

# Rate-limit knobs are documentation-only until the production limiter lands;
# validate them when operators set them.
for _rate_key in \
    RATE_OTP_SEND_PER_DESTINATION_15M \
    RATE_OTP_SEND_PER_IP_HOUR \
    RATE_OTP_VERIFY_PER_CHALLENGE \
    RATE_OTP_VERIFY_PER_IP_HOUR \
    RATE_ORDER_CREATE_PER_CUSTOMER_HOUR \
    RATE_ORDER_CREATE_PER_IP_HOUR \
    RATE_CANCEL_PER_CUSTOMER_HOUR \
    RATE_PAIR_PER_IP_15M \
    RATE_ADMIN_LOGIN_PER_IP_15M \
    RATE_MEDIA_UPLOAD_PER_ADMIN_HOUR \
    RATE_MEDIA_GET_PER_IP_MINUTE; do
    _rate=$(env_value "$_rate_key" "$ENV_FILE")
    if [ -n "$_rate" ]; then
        case $_rate in
            ''|*[!0-9]*) die "$_rate_key must be a positive integer" ;;
        esac
        [ "$_rate" -gt 0 ] || die "$_rate_key must be a positive integer"
    fi
done

printf 'check-env: %s configuration OK (%s)\n' "$ENVIRONMENT" "$ENV_FILE"
