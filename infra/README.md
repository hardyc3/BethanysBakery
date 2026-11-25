# Infrastructure

One public hostname, one production environment, one Alpine Proxmox LXC. Cloudflare
manages HTTPS certificates; Cloudflare Tunnel is the only ingress. Backups are
Proxmox full-container backups — no pg_dump, restic, or restore automation lives
in this repo. No CI/CD, registry, or staging environment.

## Layout

- `compose.dev.yml` — local development: PostgreSQL 16 + API.
- `compose.prod.yml` — the production stack on the LXC: `web`, `api`, `postgres`,
  `cloudflared`. The only published host port is metrics (`0.0.0.0:9090`), gated
  by the owner firewall (`PROMETHEUS_SCRAPE_CIDR`). The tunnel exposes one
  hostname with path ingress: `/admin/v1` → `api:8080`, `/api` → `api:8080`,
  `/` → `web:80`. No `/actuator/**` route exists at the edge.
- `cloudflared/config.yml.example` — route reference for the named tunnel. WAF,
  geo, and path-scoped mTLS rules are owned by the runbooks.
- `.env.example` — local-safe defaults plus production variables. `setup-alpine.sh`
  copies it to `/opt/bethanys-bakery/.env` and generates strong secrets.
- `setup-alpine.sh` — idempotent one-time LXC bootstrap. Installs Docker/Compose,
  git, openssl, curl, jq; creates the service account, directories, device CA,
  and the single `.env`. Never starts the stack.
- `scripts/` — `generate-ca.sh`, `pair-device.sh`, `bootstrap-admin.sh`,
  `status.sh`, `logs.sh`, `dev.sh`. Deployments use raw `git pull` and
  `docker compose` commands (see `runbooks/deploy.md`).
- `runbooks/` — the operational procedures listed below.
- `tests/test-scripts.sh` — `bash -n` over the remaining shell scripts.

W8 owns `web/Dockerfile` and `web/nginx.conf`. Until then Compose uses the
`WEB_IMAGE` placeholder (default `httpd:2.4-alpine`); do not add a competing
nginx config here. W8's nginx `/api` proxy is for local development only and is
not used for production ingress.

## Local compose

Starts PostgreSQL 16 and the API for local development.

```bash
docker compose -f compose.dev.yml --env-file .env.example up --build
```

- Postgres exposed on `127.0.0.1:5432` only.
- API on `http://localhost:8080`; actuator/Prometheus on `http://127.0.0.1:9090`
  (`/actuator/health/readiness`, `/actuator/prometheus`).
- Config via env vars; defaults in `compose.dev.yml`, example in `.env.example`.
- Uploaded images persist in named volume `media-data`, mounted at `/data/media`
  in the API container. Managed by `MEDIA_ROOT` / `MEDIA_MAX_UPLOAD_BYTES`.

## Production deploy

One-time host setup, then deploy from the repo checkout inside the LXC:

```bash
sudo ./setup-alpine.sh                         # once; idempotent
# Fill tunnel token, Mailgun/Textbee secrets, ADMIN_ALERT_EMAIL and
# ADMIN_ALERT_PHONE in /opt/bethanys-bakery/.env (mode 600).
# Restrict tcp/9090 to PROMETHEUS_SCRAPE_CIDR before exposing metrics.
# Upload the device CA to Cloudflare (runbooks/cloudflare-mtls-ca.md).

cd /opt/bethanys-bakery
git pull --ff-only
docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build
sh infra/scripts/status.sh
```

Schema migrations run at API startup (Flyway). Rollback is code-first: check out
the previous commit and re-run the same Compose command; data recovery is a
Proxmox full-container snapshot restore owned by the operator. See
`runbooks/deploy.md` and `runbooks/rollback.md`.

## Backups

Proxmox full-container backups only. They must cover the whole LXC, including
Docker volumes (`postgres-data`, `media-data`), `.env`, and the device CA key.
Verify snapshot scope once, then periodically; keep off-repo copies of `.env`
and `/opt/bethanys-bakery/secrets/device-ca.key` in the owner's secret store.
No backup scripts, retention timers, offsite copies, or restore drills are
defined in this repo.

## Runbooks

- `deploy.md` — git pull + Compose build/start, verification, rollback pointer.
- `rollback.md` — code rollback and Proxmox snapshot recovery.
- `cloudflare-tunnel.md` — one-hostname ingress, WAF, geo, cache note, probes.
- `cloudflare-mtls-ca.md` — device CA upload and path-scoped mTLS rule.
- `device-pairing.md` / `device-revocation.md` — one active admin device.
- `admin-recovery.md` — break-glass admin password/TOTP recovery.
- `mailgun-setup.md` / `textbee-setup.md` — provider and admin alert setup.
- `media-reconciliation.md` — manual orphan sweep.
- `incident-response.md` / `disk-full.md` — containment and host cleanup.

Deferred work (S9–S12, W8) is in the S8 follow-up register:
`docs/specs/server/S8-review-followups.md#follow-up-register-all-routed-none-implemented-here`.
