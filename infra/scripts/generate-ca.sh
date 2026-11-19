#!/bin/sh
# Idempotent private device CA for Cloudflare mTLS (EC P-256, 10 years).
set -eu

die() {
    printf 'generate-ca: %s\n' "$*" >&2
    exit 1
}

CA_DIR=${1:-/opt/bethanys-bakery/secrets}
CA_KEY=$CA_DIR/device-ca.key
CA_CRT=$CA_DIR/device-ca.crt

command -v openssl >/dev/null 2>&1 || die "openssl is required"

if [ -e "$CA_KEY" ] || [ -e "$CA_CRT" ]; then
    if [ -f "$CA_KEY" ] && [ -f "$CA_CRT" ] && [ -s "$CA_KEY" ] && [ -s "$CA_CRT" ]; then
        printf 'generate-ca: CA already present at %s (unchanged)\n' "$CA_DIR"
        exit 0
    fi
    die "partial CA found in $CA_DIR; resolve device-ca.key/device-ca.crt manually"
fi

umask 077
mkdir -p "$CA_DIR"
chmod 700 "$CA_DIR"

openssl ecparam -name prime256v1 -genkey -noout -out "$CA_KEY"
openssl req -new -x509 -sha256 -key "$CA_KEY" -out "$CA_CRT" -days 3650 \
    -subj "/C=US/O=Bethanys Bakery/CN=Bethanys Bakery Device CA"

chmod 600 "$CA_KEY" "$CA_CRT"
printf 'generate-ca: created %s and %s\n' "$CA_KEY" "$CA_CRT"
printf 'generate-ca: upload %s to Cloudflare mTLS for the admin API hostname\n' "$CA_CRT"
