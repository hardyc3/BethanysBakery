# Admin password / TOTP / lockout recovery

Prerequisites
- Operator has DB access via the running stack and read access to
  `/opt/bethanys-bakery/.env` (mode 600).
- Confirm the request is from the owner; record the ticket ID before changing
  credentials.
- Use a paired device for normal access; this runbook is break-glass. Owner holds
  a Proxmox full-container snapshot taken before the change.

Identify the admin
```bash
cd /opt/bethanys-bakery
ENV_FILE=/opt/bethanys-bakery/.env
COMPOSE="docker compose -f infra/compose.prod.yml --env-file $ENV_FILE"
DB_USER=$(grep '^DB_USER=' "$ENV_FILE" | cut -d= -f2)
DB_NAME=$(grep '^DB_NAME=' "$ENV_FILE" | cut -d= -f2)
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
  -c "SELECT id, email, status, failed_login_count, locked_until FROM admin_user ORDER BY created_at;"
ADMIN_ID=<uuid>
```

Lockout only (credentials still known)
```bash
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
  -c "UPDATE admin_user SET failed_login_count = 0, locked_until = NULL, updated_at = now() WHERE id = '$ADMIN_ID';"
```

Lost password or TOTP (controlled bootstrap)
```bash
# 1. Disable the lost account so bootstrap-admin can create a fresh ACTIVE admin.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
  -c "UPDATE admin_user SET status = 'DISABLED', failed_login_count = 0, locked_until = NULL, updated_at = now() WHERE id = '$ADMIN_ID';"

# 2. Bootstrap a new admin (prints TOTP URI + 10 recovery codes ONCE).
umask 077
read -rsp 'New admin password (12+ chars): ' ADMIN_BOOTSTRAP_PASSWORD; echo
export ADMIN_BOOTSTRAP_PASSWORD
sh infra/scripts/bootstrap-admin.sh admin@cherry-home.com "Bethany"
unset ADMIN_BOOTSTRAP_PASSWORD
```
Store the printed TOTP URI and recovery codes in the owner's password manager.
Revoking/replacing the device is required if the old device is lost; only one
admin device may be `ACTIVE` (`device-pairing.md`, `device-revocation.md`).

Rotate TOTP only without changing password (optional)
```bash
set -a; . "$ENV_FILE"; set +a
TOTP_SECRET=$(openssl rand -base64 96 | tr -dc 'A-Z2-7' | head -c 32)
ENC=$(printf '%s' "$TOTP_SECRET" | APP_ENCRYPTION_KEY="$APP_ENCRYPTION_KEY" \
  java infra/runbooks/tools/CryptoColumns.java encrypt)
HASHES=$(umask 077; for i in $(seq 1 10); do
    raw=$(openssl rand -base64 96 | tr -dc 'A-Z2-7' | head -c 10)
    printf '%s-%s\n' "${raw:0:5}" "${raw:5:5}"
  done | tee /tmp/admin-recovery-codes.txt | tr -d '-' | tr '[:lower:]' '[:upper:]' \
    | while read -r c; do printf '%s' "$c" | sha256sum | cut -d' ' -f1; done \
    | jq -Rsc 'split("\n") | map(select(length > 0))')
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
  -v id="$ADMIN_ID" -v secret="$ENC" -v codes="$HASHES" \
  -c "UPDATE admin_user SET totp_secret_encrypted = :'secret', recovery_codes_hash = :'codes'::jsonb, failed_login_count = 0, locked_until = NULL, updated_at = now() WHERE id = :'id'::uuid;"
rm -f /tmp/admin-recovery-codes.txt
```
Enroll the new base32 secret (`$TOTP_SECRET`) in the authenticator app. Do not
print it to logs.

Verification
- Admin login with new password/TOTP succeeds; old TOTP/recovery codes fail.
- `GET /admin/v1/devices` from a freshly paired device works; old revoked devices still return 401.
- `ADMIN_BOOTSTRAPPED` audit event exists; owner has the codes.

Rollback
- If bootstrap fails, re-enable the original admin (`status='ACTIVE'`) and clear
  lockout; no data is deleted. If TOTP rotation fails, restore the whole LXC
  from the pre-change Proxmox snapshot and keep the original `.env` value.
  Never restore only part of a container while changed rows exist.

Notify
- Owner immediately; security owner + ops on-call for any suspected compromise.
  Never send secrets or recovery codes over chat/email.
