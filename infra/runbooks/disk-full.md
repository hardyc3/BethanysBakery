# Disk full (media / Docker / LXC)

Prerequisites
- Root/sudo on the Alpine LXC; identify whether `/`, `/opt/bethanys-bakery`, or
  `/var/lib/docker` is full. Proxmox snapshot storage is outside the LXC.
- Do not delete DB volume data, active media, `.env`, or CA keys. No restic
  repository, pg_dump files, or backup scripts exist in this setup.

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

# 2. Orphan media files after review (media-reconciliation.md).
#    Do NOT rm -rf /data/media or the media volume.

# 3. If a single container log grew despite json-file rotation, truncate it.
: > "$(docker inspect --format='{{.LogPath}}' <container-id>)"

# 4. If the LXC disk itself is exhausted, ask the owner to grow the Proxmox
#    volume or prune old Proxmox snapshots. Do not delete the current snapshot.
```

Verification
```bash
df -h / /opt/bethanys-bakery /var/lib/docker
sh /opt/bethanys-bakery/infra/scripts/status.sh
curl -fsS https://bakery.cherry-home.com/api/v1/config
```
- API health returns 200; media upload and download succeed; a recent Proxmox
  container snapshot still exists.

Rollback
- Cleanup is irreversible for pruned images/logs. If a required image was
  pruned, rebuild in the LXC with:
  `cd /opt/bethanys-bakery && git pull --ff-only && docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build`.

Notify
- Owner + ops on-call immediately for SEV1 disk exhaustion; record freed bytes
  and follow-up capacity action.
