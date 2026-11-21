# Incident response

Severity
- SEV1: production down, data loss/corruption, or secret/device compromise. Page owner + ops + security immediately.
- SEV2: degraded orders/notifications or one admin/customer blocked. Notify owner + ops on-call in status channel.
- SEV3: cosmetic or isolated non-blocking failure. Track in ops log.

Prerequisites
- Access to `/opt/bethanys-bakery`, Cloudflare dashboard, restic repo, and the owner's status channel.
- Keep a ticket ID and timestamps in UTC. Never paste secrets, OTPs, or recovery codes into chat.

Triage commands
```bash
cd /opt/bethanys-bakery
sh infra/scripts/status.sh prod
LOG_LINES=500 sh infra/scripts/logs.sh prod postgres api web cloudflared > /tmp/incident-logs.txt
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/prod/.env stats --no-stream
df -h / /opt/bethanys-bakery
curl -fsS https://bakery.cherry-home.com/actuator/health 2>/dev/null || true
```
Preserve evidence before restarting: copy `/tmp/incident-logs.txt` to the ticket, capture `docker inspect` for affected containers, and note recent deploys (`infra/PREVIOUS_DEPLOYED_COMMIT.*`).

Containment
- Compromised device: revoke via `device-revocation.md`; rotate the device CA if more than one device is suspect (`cloudflare-mtls-ca.md`).
- Abuse from an IP: Cloudflare → WAF → IP Access Rules → Block, or add a temporary rate-limit rule on the affected hostname.
- Suspected secret exposure: rotate that secret (JWT via env + redeploy, `APP_ENCRYPTION_KEY` via `encryption-key-rotation.md`, RESTIC password + re-init repo only with security approval).
- Disk exhaustion: follow `disk-full.md` before restarting anything.

Recovery
- Bad release: follow `rollback.md` (code rollback first).
- Bad data or failed migration: `database-restore.md` / `backup-restore.md`; use the last known-good snapshot and `--yes` only after owner approval.
- Provider outage: switch to the documented fallback (email vs SMS) and note failed notifications in the admin app.

Verification
- All four services healthy; customer config, one menu date, one order, and one media URL succeed.
- Security probes from `security-scanning.md` repeated if the incident touched edge/auth.
- Incident timeline and root-cause notes written to the ops log.

Rollback
- Any containment rule added in Cloudflare must be reviewed/removed before closing the incident. Do not leave temporary IP blocks or disabled WAF rules in place silently.

Notify
- SEV1: owner + security + ops on-call immediately, then status channel every 30 min.
- SEV2/SEV3: owner + ops on-call in status channel; `ADMIN_ALERT_EMAIL` for customer-impacting outages.
