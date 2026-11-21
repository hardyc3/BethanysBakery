# Disk full (media / backups / Docker)

Prerequisites
- Root/sudo on the Alpine host; identify whether `/`, `/opt/bethanys-bakery`, or `/var/lib/docker` is full.
- Do not delete DB volume data, active media, `.env`, or CA keys.

Locate pressure
```bash
df -h / /opt/bethanys-bakery /var/lib/docker
du -h -d 2 /opt/bethanys-bakery | sort -h | tail -n 20
docker system df -v
du -h -d 2 /var/lib/docker/containers 2>/dev/null | sort -h | tail -n 20
```

Safe cleanup (in order)
```bash
# 1. Docker logs/build cache only.
docker image prune -f                       # unused images only; never `docker system prune -a --volumes`
docker builder prune -f

# 2. Old restic snapshots (retention is already 7/4/6; this is an emergency tightening).
set -a; . /opt/bethanys-bakery/prod/.env; set +a
restic forget --keep-daily 3 --keep-weekly 2 --keep-monthly 2 --prune
restic check

# 3. Orphan media files after review (media-reconciliation.md).
#    Do NOT rm -rf /data/media or the media volume.

# 4. If a single container log grew despite json-file rotation, truncate it.
: > "$(docker inspect --format='{{.LogPath}}' <container-id>)"
```

Verification
```bash
df -h / /opt/bethanys-bakery /var/lib/docker
sh /opt/bethanys-bakery/infra/scripts/status.sh prod
curl -fsS https://bakery.cherry-home.com/api/v1/config
```
- API health returns 200; media upload and download succeed; restic repo still has a valid recent snapshot.

Rollback
- Cleanup is irreversible for pruned images/logs. If a required image was pruned, rebuild with `sh infra/scripts/build.sh prod`. If a needed restic snapshot was removed, there is no rollback — restore from offsite repository if configured.

Notify
- Owner + ops on-call immediately for SEV1 disk exhaustion; record freed bytes and follow-up capacity action.
