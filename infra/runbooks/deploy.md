# Deploy (production LXC)

Prerequisites
- Root/sudo on the single Alpine Proxmox LXC; repo checked out at
  `/opt/bethanys-bakery`.
- `infra/setup-alpine.sh` already run; `/opt/bethanys-bakery/.env` is mode 600
  with `CLOUDFLARE_TUNNEL_TOKEN`, Mailgun/Textbee secrets, `ADMIN_ALERT_EMAIL`,
  and optional `ADMIN_ALERT_PHONE` filled.
- Device CA uploaded to Cloudflare with the path-scoped mTLS rule
  (`cloudflare-mtls-ca.md`); tunnel routes are configured
  (`cloudflare-tunnel.md`).
- Owner has taken a Proxmox full-container snapshot before risky changes.
  Admin alert destinations are masked in API responses and logs.

Commands (no deploy scripts; raw git + Compose inside the LXC)
```bash
cd /opt/bethanys-bakery
git rev-parse HEAD > "PREVIOUS_DEPLOYED_COMMIT.$(date -u +%Y%m%dT%H%M%SZ)"
git pull --ff-only
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build --remove-orphans
sh infra/scripts/status.sh
```

Flyway migrations run at API startup. One API replica only. The stack is
`postgres`, `api`, `web`, `cloudflared`; no backup profile, no staging project.

Verification
```bash
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env \
  exec -T api curl -fsS http://127.0.0.1:8080/actuator/health/readiness
curl -fsS https://bakery.cherry-home.com/api/v1/config
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H 'CF-IPCountry: CA' https://bakery.cherry-home.com/api/v1/config          # 403 COUNTRY_BLOCKED
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H 'CF-IPCountry: US' -H 'Origin: https://evil.example' \
  https://bakery.cherry-home.com/api/v1/auth/csrf                              # 403, no CORS
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/admin/v1/app-info                             # 403 at edge, no cert
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://bakery.cherry-home.com/admin/v1/devices/pair                        # reaches origin (rate limited)
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env \
  ps --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0|::' || echo 'no published ports'
```
- Admin API with a valid device cert/key plus `X-Device-Key` returns 200 on
  `/admin/v1/app-info`; without them it is blocked at edge or returns 401 at origin.
- External-network origin probe in `cloudflare-tunnel.md` must show the origin
  unreachable except through Cloudflare.
- Logs contain no OTP, token, email, phone, or secret; alert destinations are masked.

Deferred verification
- S7's unchecked probes/scans/restore drill, the S5 test gaps, and tooling work
  are routed to S9–S12 in
  `docs/specs/server/S8-review-followups.md#follow-up-register-all-routed-none-implemented-here`.
  Do not reintroduce a security-scanning or backup runbook here.

Rollback
- Follow `rollback.md`. Flyway migrations are forward-only; do not hand-edit
  schema versions.

Notify
- Owner + ops on-call in the status channel before start and after verification;
  for customer-impacting failures also send to `ADMIN_ALERT_EMAIL`.
