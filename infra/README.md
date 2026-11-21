# Infrastructure

## Layout (S7)

- `compose.dev.yml` — local development: PostgreSQL 16 + API.
- `compose.prod.yml` / `compose.staging.yml` — deployed stacks: `web`, `api`,
  `postgres`, `cloudflared`, plus an optional `backup` profile. Cloudflare Tunnel
  is the only ingress; no API/Postgres host ports are published. Staging is
  isolated by project name, networks, volumes, DB, hostnames, and tunnel token.
- `cloudflared/config.yml.example` — token-only setup route reference. WAF, geo,
  cache, and admin mTLS rules are owned by the runbooks.
- `.env.example` — dev-safe defaults plus S7 deployment variables. `setup-alpine.sh`
  generates `/opt/bethanys-bakery/{prod,staging}/.env` from it with strong secrets.
- `setup-alpine.sh` — idempotent Alpine host bootstrap. Installs Docker/Compose,
  restic, openssl, curl, jq, tar, gzip; creates the service account, directories,
  device CA, and per-env `.env` files. Never starts the stack.
- `scripts/` — `build.sh`, `deploy.sh`, `status.sh`, `logs.sh`, `backup.sh`,
  `restore.sh`, `bootstrap-admin.sh`, `pair-device.sh`, `generate-ca.sh`,
  `check-env.sh`.

W8 owns `web/Dockerfile` and `web/nginx.conf`. Until then Compose uses the
`WEB_IMAGE` placeholder (default `httpd:2.4-alpine`); do not add a competing
nginx config here.

## Local compose (S1)

Starts PostgreSQL 16 and the API for local development.

```bash
docker compose -f compose.dev.yml --env-file .env.example up --build
```

- Postgres exposed on `127.0.0.1:5432` only.
- API on `http://localhost:8080`.
- Config via env vars; defaults in `compose.dev.yml`, example in `.env.example`.
- Uploaded images persist in named volume `media-data`, mounted at `/data/media`
  in the API container. Managed by `MEDIA_ROOT` / `MEDIA_MAX_UPLOAD_BYTES`.

## Deployment workflow (S7)

```bash
sudo ./setup-alpine.sh                       # once per host; idempotent
# Fill tunnel tokens and provider secrets in /opt/bethanys-bakery/{prod,staging}/.env
sudo ./scripts/check-env.sh prod
sudo ./scripts/deploy.sh prod                 # builds, starts, waits for health
./scripts/status.sh prod
./scripts/logs.sh prod api
```

Schema migrations run at API startup (Flyway). Backups are manual:

```bash
./scripts/backup.sh prod
./scripts/restore.sh staging <snapshot> --yes
```

Backups store a `pg_dump -Fc` file and a media tar in restic
(`RESTIC_REPOSITORY`, default `/opt/bethanys-bakery/backups/restic/<env>`),
with retention 7 daily / 4 weekly / 6 monthly. `.env` files, the CA private key,
and source are not included. Expected RPO is the last completed backup; RTO is
under one hour at this scale. Schedule `backup.sh` with a host cron or systemd
timer; keep that timer outside this repo.

Operational procedures (prerequisites, exact commands, verification, rollback,
notifications) live in `infra/runbooks/`: `deploy.md`, `rollback.md`,
`backup-restore.md`, `cloudflare-tunnel.md`, `cloudflare-cache.md`,
`cloudflare-mtls-ca.md`, `media-reconciliation.md`, `device-pairing.md`,
`device-revocation.md`, `certificate-pinning-rotation.md`, `mailgun-setup.md`,
`textbee-setup.md`, `fcm-setup.md`, `incident-response.md`, `disk-full.md`, and
`database-restore.md`.
