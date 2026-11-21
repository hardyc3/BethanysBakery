# Rotate APP_ENCRYPTION_KEY

Why / policy
- `APP_ENCRYPTION_KEY` encrypts `admin_user.totp_secret_encrypted` and `otp_challenge.destination_encrypted`. Rotating the env value alone breaks admin TOTP login and pending OTP challenges.
- Use the offline helper `infra/runbooks/tools/CryptoColumns.java` (same AES-GCM/base64url format as `CryptoService`) for an in-place migration. Alternative: force TOTP re-enrollment via `admin-recovery.md` and let pending challenges expire. Never rotate without one of these migrations.

Prerequisites
- Owner-approved maintenance window; fresh backup (`sh infra/scripts/backup.sh prod`).
- Java 21 on host; `psql`, `jq`, `docker` available.
- API/web stopped so no new encrypted rows are written during migration.

Commands
```bash
cd /opt/bethanys-bakery
ENV=prod
ENV_FILE=/opt/bethanys-bakery/$ENV/.env
COMPOSE="docker compose -f infra/compose.$ENV.yml --env-file $ENV_FILE"
DB_USER=$(grep '^DB_USER=' "$ENV_FILE" | cut -d= -f2)
DB_NAME=$(grep '^DB_NAME=' "$ENV_FILE" | cut -d= -f2)

set -a; . "$ENV_FILE"; set +a
OLD_APP_ENCRYPTION_KEY="$APP_ENCRYPTION_KEY"
read -rsp 'New APP_ENCRYPTION_KEY (base64, >=32 bytes): ' NEW_APP_ENCRYPTION_KEY; echo
export OLD_APP_ENCRYPTION_KEY NEW_APP_ENCRYPTION_KEY

$COMPOSE stop api web

# Export rows with stable ids.
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -AtF "$(printf '\t')" \
  -c "SELECT id, totp_secret_encrypted FROM admin_user WHERE totp_secret_encrypted <> ''" \
  > /tmp/key-rotate-admin.tsv
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -AtF "$(printf '\t')" \
  -c "SELECT id, destination_encrypted FROM otp_challenge WHERE expires_at > now()" \
  > /tmp/key-rotate-otp.tsv

# Re-encrypt with the new key (output stays id<TAB>ciphertext).
java infra/runbooks/tools/CryptoColumns.java rotate-tsv < /tmp/key-rotate-admin.tsv > /tmp/key-rotate-admin.new.tsv
java infra/runbooks/tools/CryptoColumns.java rotate-tsv < /tmp/key-rotate-otp.tsv > /tmp/key-rotate-otp.new.tsv

$COMPOSE cp /tmp/key-rotate-admin.new.tsv postgres:/tmp/key-rotate-admin.new.tsv
$COMPOSE cp /tmp/key-rotate-otp.new.tsv postgres:/tmp/key-rotate-otp.new.tsv
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" <<'SQL'
CREATE TEMP TABLE rot_admin (id uuid PRIMARY KEY, val text);
CREATE TEMP TABLE rot_otp (id uuid PRIMARY KEY, val text);
\copy rot_admin FROM '/tmp/key-rotate-admin.new.tsv' WITH (FORMAT csv, DELIMITER E'\t')
\copy rot_otp FROM '/tmp/key-rotate-otp.new.tsv' WITH (FORMAT csv, DELIMITER E'\t')
UPDATE admin_user a SET totp_secret_encrypted = r.val, updated_at = now()
  FROM rot_admin r WHERE a.id = r.id;
UPDATE otp_challenge o SET destination_encrypted = r.val
  FROM rot_otp r WHERE o.id = r.id;
SQL
$COMPOSE exec -T postgres rm -f /tmp/key-rotate-admin.new.tsv /tmp/key-rotate-otp.new.tsv

# Update env last, then redeploy.
sed -i "s|^APP_ENCRYPTION_KEY=.*|APP_ENCRYPTION_KEY=$NEW_APP_ENCRYPTION_KEY|" "$ENV_FILE"
chmod 600 "$ENV_FILE"
unset OLD_APP_ENCRYPTION_KEY NEW_APP_ENCRYPTION_KEY
sh infra/scripts/deploy.sh "$ENV"
rm -f /tmp/key-rotate-*.tsv
```
Verify the helper before the window with `printf 'test' | APP_ENCRYPTION_KEY="$KEY" java ... encrypt | APP_ENCRYPTION_KEY="$KEY" java ... decrypt`.

Verification
- Admin login with existing TOTP succeeds (no re-enrollment needed).
- Start an OTP request/verify with both EMAIL and SMS before/after rotation; both succeed.
- `restic` snapshot from before rotation remains restorable with the old key stored offline.
- No key material in shell history, git, or logs; `.env` mode 600.

Rollback
- If migration fails before env update: keep old `.env`, re-run deploy, and investigate. If migration partially applied, restore the pre-rotation snapshot (`database-restore.md`) and the old `.env` value together. Do not mix old key with migrated rows.

Notify
- Owner + security owner + ops on-call before start and after verification; record ticket ID and rotate date.
