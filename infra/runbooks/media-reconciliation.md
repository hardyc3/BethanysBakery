# Media orphan reconciliation

Why
- Uploads write files before the DB commit; `MediaService` cleans up on rollback. A crash after commit but before the `afterCommit` delete can leave files that no `ACTIVE` media row references. Run this sweep manually after incidents and monthly.

Prerequisites
- Production stack running on the LXC; host has `psql` access through the compose network.
- Media files live in `MEDIA_ROOT=/data/media` inside the `api` container / named volume.
- Expected keys: `<storageKey>` plus `400.webp`, `800.webp`, `1600.webp` in the same directory for `ACTIVE` media.

Preview (no changes)
```bash
cd /opt/bethanys-bakery
ENV_FILE=/opt/bethanys-bakery/.env
COMPOSE="docker compose -f infra/compose.prod.yml --env-file $ENV_FILE"

$COMPOSE exec -T postgres psql -U "$(grep '^DB_USER=' "$ENV_FILE" | cut -d= -f2)" \
  -d "$(grep '^DB_NAME=' "$ENV_FILE" | cut -d= -f2)" -Atc "
  SELECT storage_key FROM media WHERE status = 'ACTIVE'
  UNION ALL
  SELECT replace(storage_key, '/original.webp', '') || '/' || s || '.webp'
  FROM media CROSS JOIN (VALUES ('400'),('800'),('1600')) AS v(s)
  WHERE status = 'ACTIVE';" | sort -u > /tmp/media-expected.txt

$COMPOSE exec -T api sh -c 'cd /data/media && find . -type f -name "*.webp" | sed "s|^\./||" | sort' \
  > /tmp/media-actual.txt

comm -13 /tmp/media-expected.txt /tmp/media-actual.txt > /tmp/media-orphans.txt
wc -l /tmp/media-orphans.txt
head -n 50 /tmp/media-orphans.txt
```
Review every path. Expected shape: `YYYY/MM/<uuid>/(original|400|800|1600).webp`. Never delete anything not listed in `/tmp/media-orphans.txt`.

Delete reviewed orphans
```bash
grep -E '^[0-9]{4}/[0-9]{2}/[0-9a-f-]+/(original|400|800|1600)\.webp$' /tmp/media-orphans.txt > /tmp/media-orphans.safe.txt
diff /tmp/media-orphans.txt /tmp/media-orphans.safe.txt   # must be empty; investigate otherwise
while IFS= read -r key; do
  $COMPOSE exec -T api sh -c 'rm -f -- "/data/media/$1"' _ "$key"
done < /tmp/media-orphans.safe.txt
```

Verification
- Re-run preview; `/tmp/media-orphans.txt` is empty.
- `GET https://bakery.cherry-home.com/api/v1/media/<active-uuid>?size=800` still returns 200 and correct WebP bytes.
- Audit log shows no new `MEDIA_DELETED` for the swept files (this is a filesystem-only cleanup).

Rollback
- No DB changes are made. If a file was deleted in error, restore it from a
  Proxmox full-container snapshot (owner) or re-upload the asset through admin.

Notify
- Owner + ops on-call before deleting; record count and paths in the ops log.
