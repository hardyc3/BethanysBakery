# Backup, retention, and restore drill

Prerequisites
- Host installed by `infra/setup-alpine.sh`; `restic`, `jq`, `docker`, `curl` available.
- `/opt/bethanys-bakery/<env>/.env` contains `RESTIC_REPOSITORY`, `RESTIC_PASSWORD`, `DB_*`.
- Secrets are never part of the backup: back up the DB dump and media archive only.

Run a backup
```bash
cd /opt/bethanys-bakery
sh infra/scripts/backup.sh prod          # or staging
```
`backup.sh` writes `pg_dump -Fc` + `media-*.tar.gz`, sends them to restic, applies
`--keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune`, runs `restic check`, and exits non-zero on any failure.

Host schedule (outside the repo, crontab on the Alpine host)
```cron
30 2 * * * /opt/bethanys-bakery/infra/scripts/backup.sh prod >> /var/log/bakery-backup.log 2>&1
```

Inspect / verify
```bash
set -a; . /opt/bethanys-bakery/prod/.env; set +a
restic snapshots --tag prod --latest 5
SNAPSHOT=$(restic snapshots --tag prod --latest 1 --json | jq -r '.[0].short_id')
restic check
restic ls "$SNAPSHOT" | grep -E 'db-prod-.*\.dump|media-prod-.*\.tar\.gz'
```

Restore
```bash
sh infra/scripts/restore.sh prod "$SNAPSHOT" --yes
sh infra/scripts/status.sh prod
```
For DB-only restore see `database-restore.md`.

Offsite R2/S3 (optional)
- Set `RESTIC_REPOSITORY=s3:https://<account>.r2.cloudflarestorage.com/<bucket>` and the standard
  `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in the env file (mode 600). Do not commit them.

RPO / RTO / drill
- RPO = last successful snapshot (nightly schedule → ≤24 h). Alert on any failed backup; run a manual backup before risky changes.
- RTO target <1 h at current scale: restic restore + `pg_restore` + media extraction + health check.
- Monthly restore drill: in `staging`, on the first Monday, run `sh infra/scripts/restore.sh staging <latest-staging-snapshot> --yes`, then verify one order, one menu, and one media URL. Record snapshot ID, duration, and result in the ops log.

Verification
- `restic check` clean; snapshot contains both `db-*.dump` and `media-*.tar.gz`.
- API healthy after restore; orders/menus exist; media loads.
- No dump/archive left in `/tmp` after script exits (trap cleanup).

Rollback
- If a restore fails, stop write traffic (`docker compose ... stop api web`), keep the extracted dump in place, and restore the previous known-good snapshot. Never start the API against a half-restored DB.

Notify
- Owner + ops on-call on backup failure or drill result; notify support before any production restore.
