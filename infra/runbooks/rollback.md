# Rollback (prod / staging)

Prerequisites
- Keep the previous git commit from `infra/PREVIOUS_DEPLOYED_COMMIT.<timestamp>` (written by `deploy.md`).
- Keep the pre-deploy restic snapshot ID from `backup.sh` output.
- Never down-migrate Flyway versions. Roll back code first; restore data only for bad data/migrations.

Commands
```bash
cd /opt/bethanys-bakery
ENV=prod
PREV=$(ls -1t infra/PREVIOUS_DEPLOYED_COMMIT.* | head -n 1 | xargs cat)

# 1. Rebuild/start the last known-good code.
git checkout --detach "$PREV"
sh infra/scripts/check-env.sh "$ENV"
sh infra/scripts/deploy.sh "$ENV"
sh infra/scripts/status.sh "$ENV"
```

Data rollback (only if the release corrupted data or applied a bad migration)
```bash
SNAPSHOT=<pre-deploy-snapshot-id>
sh infra/scripts/restore.sh "$ENV" "$SNAPSHOT" --yes
sh infra/scripts/status.sh "$ENV"
```
To restore only the database without the media archive, follow `database-restore.md`.

Verification
- `sh infra/scripts/status.sh "$ENV"` shows `postgres`, `api`, `web`, `cloudflared` healthy.
- `curl -fsS https://bakery.cherry-home.com/api/v1/config` returns 200.
- Spot-check one order, one published menu date, and one media URL.
- Confirm git HEAD and running API image match the intended rollback commit/tag.

Rollback
- Re-run this runbook with the older commit; if rollback also fails, stop the stack and escalate rather than guessing:
  `docker compose -f "infra/compose.$ENV.yml" --env-file "/opt/bethanys-bakery/$ENV/.env" down --remove-orphans` (does not delete volumes).

Notify
- Owner + ops on-call immediately; post snapshot ID, rolled-back commit, and customer impact in status channel.
