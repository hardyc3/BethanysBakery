# Rollback (production LXC)

Prerequisites
- Keep the previous git commit from `PREVIOUS_DEPLOYED_COMMIT.<timestamp>` in the
  repo checkout (written by `deploy.md`).
- Owner can restore a Proxmox full-container snapshot taken before the change.
  There is no pg_dump, restic, or in-repo restore script.
- Never down-migrate Flyway versions. Roll back code first; restore data only
  for bad data or a bad migration.

Code rollback
```bash
cd /opt/bethanys-bakery
PREV=$(ls -1t PREVIOUS_DEPLOYED_COMMIT.* | head -n 1 | xargs cat)
git checkout --detach "$PREV"
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build --remove-orphans
sh infra/scripts/status.sh
```

Data rollback (owner, only if the release corrupted data or applied a bad migration)
- Stop the LXC and restore the last known-good Proxmox full-container backup.
  That snapshot includes Docker volumes (`postgres-data`, `media-data`), `.env`,
  and the device CA key. Confirm snapshot scope before relying on it.
- After restore, start the LXC, verify git HEAD matches the intended commit, and
  re-run the raw Compose command above if the running image tag differs.

Verification
- `sh infra/scripts/status.sh` shows `postgres`, `api`, `web`, `cloudflared` healthy.
- `curl -fsS https://bakery.cherry-home.com/api/v1/config` returns 200.
- Spot-check one order, one published menu date, and one media URL.
- Confirm git HEAD and running API image match the intended rollback commit.

Rollback
- Re-run this runbook with an older commit. If rollback also fails, stop the
  stack and escalate rather than guessing:
  `docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env down --remove-orphans` (does not delete volumes).

Notify
- Owner + ops on-call immediately; post the rolled-back commit, snapshot ID, and
  customer impact in the status channel.
