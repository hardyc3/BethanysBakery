# Database-only restore

Use when media on disk is intact and only PostgreSQL needs recovery. For full stack restore use `backup-restore.md`. Requires owner approval and `--yes` confirmation for the wrapper.

Prerequisites
- Known-good restic snapshot ID (`restic snapshots --tag prod --latest 5`).
- API and web will be stopped; customers cannot place orders during the restore.
- DB credentials/RESTIC env from `/opt/bethanys-bakery/<env>/.env` (mode 600).

Commands (preferred wrapper)
```bash
cd /opt/bethanys-bakery
SNAPSHOT=<snapshot-id>
sh infra/scripts/restore.sh prod "$SNAPSHOT" --yes     # restores DB + media together
```
If you must restore only the DB, do it via `pg_restore` manually:
```bash
ENV=prod
ENV_FILE=/opt/bethanys-bakery/$ENV/.env
COMPOSE="docker compose -f infra/compose.$ENV.yml --env-file $ENV_FILE"
DB_USER=$(grep '^DB_USER=' "$ENV_FILE" | cut -d= -f2)
DB_NAME=$(grep '^DB_NAME=' "$ENV_FILE" | cut -d= -f2)
set -a; . "$ENV_FILE"; set +a

restic restore "$SNAPSHOT" --target /tmp/bb-db-restore
DB_DUMP=$(find /tmp/bb-db-restore -type f -name 'db-*.dump' | head -n 1)
[ -n "$DB_DUMP" ] || { echo 'no dump in snapshot' >&2; exit 1; }

$COMPOSE stop api web
$COMPOSE exec -T postgres pg_restore --clean --if-exists --exit-on-error \
  -U "$DB_USER" -d "$DB_NAME" < "$DB_DUMP"
$COMPOSE up -d --no-deps api
# Wait for readiness before starting web.
until $COMPOSE exec -T api curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; do sleep 5; done
$COMPOSE up -d --no-deps web
sh infra/scripts/status.sh "$ENV"
rm -rf /tmp/bb-db-restore
```

Verification
```bash
$COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
  -c "SELECT count(*) FROM orders; SELECT count(*) FROM menu_date; SELECT count(*) FROM media WHERE status='ACTIVE';"
curl -fsS https://bakery.cherry-home.com/api/v1/menu-dates
curl -fsS https://bakery.cherry-home.com/api/v1/media/<known-media-uuid>?size=800 -o /dev/null
```
Spot-check one known order number and menu date with the owner. Flyway checksums must be unchanged after restore.

Rollback
- Stop api/web and restore the previous snapshot with the same commands. Do not start the API against a partially restored DB.

Notify
- Owner + ops on-call before starting; post snapshot ID, row counts, and duration in the ticket. Support gets customer-facing status after verification.
