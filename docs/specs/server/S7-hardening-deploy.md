# S7 — Hardening, Deployment, and Operations

- Status: ready
- Depends on: S6
- Contract: no new business endpoints; operational and security behavior across the API
- Next: production readiness

## Goal

Make the system safe and operable on Alpine/Proxmox behind Cloudflare Tunnel: production compose, host setup, secrets, mTLS, geo/WAF rules, observability, backups, restore, and runbooks. Builds, deployments, and backups are manual scripts.

## Non-goals

- CI/CD pipeline.
- Kubernetes.
- Prometheus/Grafana stack.
- Automated Venmo reconciliation.
- Multi-instance scaling.
- Multi-tenant support.

## Data model — `V7__hardening.sql`

No new business tables. Add only:

- Missing indexes from `docs/domain-model.md`.
- Check constraints discovered during hardening.
- Any partial unique indexes needed for correctness.
- No destructive changes.

If an index duplicates one created earlier, leave it alone. Do not edit prior migrations.

## Security hardening

### Transport and edge

- Cloudflare Tunnel only. No published host ports for API/Postgres.
- Tunnel routes:
  - `bakery.cherry-home.com` → `web:80`
  - `bakery-admin-api.cherry-home.com` → `api:8080`
  - `bakery-pair.cherry-home.com` → `api:8080`
- Cloudflare:
  - HSTS enabled.
  - TLS 1.2+.
  - WAF managed rules.
  - US-only geo rule for `bakery.cherry-home.com` on `/api/v1/*`.
  - Admin API mTLS rule with the bakery device CA.
  - Pair hostname has no mTLS but strict rate limiting.
  - Media cache for `/api/v1/media/*`, bypass cache for availability/orders.
- Origin must not be reachable directly. Verify from an external network.

### Application checks

- Production profile rejects:
  - `ADMIN_MTLS_LOCAL_BYPASS=true`
  - default/blank secrets (including DB credentials still set to local/dev defaults such as `bakery`/`change-me`)
  - non-HTTPS public/admin URLs
  - `JWT_SECRET` shorter than 32 bytes
  - missing CA files
  - local OTP logging enabled
  - OpenAPI UI enabled
- Country check for customer API:
  - If `CLOUDFLARE_PROXY=true`, require `CF-IPCountry`.
  - Allow only `US` for `/api/v1/**`.
  - Exempt `/api/v1/webhooks/**` (provider callbacks) and `/actuator/health/**`.
  - Return 403 `COUNTRY_BLOCKED` without leaking internals.
  - Admin hosts are exempt in code; Cloudflare can still restrict them.
- Origin check:
  - If an `Origin` header is present on a customer API request, it must equal `APP_PUBLIC_BASE_URL`.
  - No CORS headers are returned for other origins.
- Rate limits:
  - OTP request: 3 per destination/15m, 10 per IP/hour.
  - OTP verify: 5 attempts per challenge, 20 per IP/hour.
  - Order create: 10 per customer/hour, 30 per IP/hour.
  - Cancel: 20 per customer/hour.
  - Pair: 10 per IP/15m.
  - Admin login: 10 per IP/15m plus account lockout.
  - Media upload: 20 per admin/hour at origin plus a Cloudflare WAF rule on `POST /admin/v1/media`.
  - Public media GET: 120 per IP/minute at origin on `GET /api/v1/media/**` (the edge cache absorbs normal traffic).
  - Return 429 `RATE_LIMITED` with `Retry-After`.
- Headers:
  - `Strict-Transport-Security`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: no-referrer`
  - `Content-Security-Policy` for the web app (set at nginx; API returns JSON only)
  - `Permissions-Policy`
  - `X-Frame-Options: DENY` or CSP frame-ancestors
- Cookies in prod: `Secure`, `HttpOnly` where applicable, `SameSite=Lax`.
- No stack traces, SQL, or internal hostnames in responses.
- Request size limits:
  - JSON max 1 MB.
  - Upload max 10 MB.
  - Reject oversized bodies with 413.
- Log redaction:
  - emails, phones, tokens, OTPs, TOTP secrets, API keys, authorization headers, cookies.
- Dependency and image scans are manual checklist items.

### Media hardening ownership

S3 implemented the origin-side upload pipeline; these follow-ups were found during the S3 quality gate and are owned here.

- Cache purge: `GET /api/v1/media/{mediaId}` is served with `Cache-Control: public, max-age=31536000, immutable`. On `MEDIA_DELETED`, purge `/api/v1/media/{mediaId}*` at Cloudflare (or set a shorter TTL that meets the takedown target). Document the chosen policy in `cloudflare-cache.md` and verify with a cached-then-deleted media probe.
- Reconciliation sweep: add a startup/maintenance job or operator script that lists files under `MEDIA_ROOT` and removes any file not referenced by an `ACTIVE` `media.storage_key` or one of its generated `400/800/1600` variant keys. Post-commit crashes can leave orphan files because deletion runs in `afterCommit`. Add runbook `media-reconciliation.md`.
- Reference integrity under concurrency: template creates (`item_template.photo_media_id`) and media deletes race under READ_COMMITTED. Enforce `MEDIA_IN_USE` atomically (pessimistic lock on the `media` row, serializable transaction, or a DB trigger that blocks `DELETED` while referenced) and add an integration test. S4 must reuse the same mechanism when offerings reference photos.
- Nested collection caps: `customizationGroups` and per-group `options` are unbounded in the catalog DTOs and `openapi.yaml` (`ItemTemplateWrite`, `CustomizationGroupWrite`). Add `maxItems` to those OpenAPI schemas and matching `@Size(max = …)` to the S3 and S4 DTOs. Choose generous limits that cannot reject a legitimate catalog; these complement, not replace, the 1 MB JSON cap.
- Original filename: `media.original_filename` is stored sanitized and is never returned by the API or written to logs. Confirm the retention need; if unused, plan a forward-only migration to drop the column. Until then, keep it out of responses and logs.
- Dependency pinning: pin or verify the S3 image dependencies (`imageio-webp`, `webp-imageio`, `metadata-extractor`, `thumbnailator`) with Gradle verification metadata or digests, and include them in the dependency/image scan checklist.

### Admin recovery and key rotation

- Runbook `admin-recovery.md`: lost password/TOTP/admin lockout → operator runs CLI recovery (rotate TOTP secret, reset password hash, or controlled bootstrap flow) on host with DB access; re-pair device if cert/key lost.
- `APP_ENCRYPTION_KEY` rotation: decrypt existing TOTP secrets with old key and re-encrypt with new key during a maintenance step, or require TOTP re-enrollment. Document exact procedure; never rotate key without migration.

### mTLS and certificates

- Setup generates a private CA if absent:
  - `/opt/bethanys-bakery/secrets/device-ca.key`
  - `/opt/bethanys-bakery/secrets/device-ca.crt`
- Operator uploads the CA certificate to Cloudflare mTLS for `bakery-admin-api`.
- Origin requires `Cf-Authenticated-Client-Cert-Verified: true` in addition to the certificate header. Reject requests where Cloudflare marks the certificate unverified or revoked.
- Device certificates: 180 days.
- Pairing issues certificates. Revocation:
  - App-level device status blocks immediately.
  - Cloudflare CA/CRL handling documented as a manual step.
- Certificate renewal is a manual procedure: pair a new certificate or add a renewal endpoint only if a later spec requires it.
- Pin set rotation runbook included.

## Deployment artifacts

### `infra/setup-alpine.sh`

- Idempotent.
- Requires root.
- Installs: `docker`, `docker-cli-compose`, `restic`, `openssl`, `curl`, `jq`, `tar`, `gzip`.
- Enables Docker at boot.
- Creates:
  - `/opt/bethanys-bakery/prod`
  - `/opt/bethanys-bakery/staging`
  - `/opt/bethanys-bakery/secrets`
  - `/opt/bethanys-bakery/backups`
  - `/opt/bethanys-bakery/media`
- Creates a service user and group; sets ownership.
- Generates:
  - `/opt/bethanys-bakery/prod/.env` and `/opt/bethanys-bakery/staging/.env` from `infra/.env.example` if absent
  - database password
  - JWT secret
  - app encryption key
  - restic password
  - device CA if absent
- Sets file modes `600`/`700` for secrets.
- Does not start the stack.
- Prints next steps.

### Compose

- `infra/compose.prod.yml`:
  - `web`, `api`, `postgres`, `cloudflared`, optional `backup` profile.
  - internal networks `edge` and `backend`.
  - named volumes `postgres-data`, `media-data` (mounted at `/data/media` in `api`).
  - mount `/opt/bethanys-bakery/secrets` read-only into `api` for device CA (`device-ca.key`, `device-ca.crt`) and other secret files as needed.
  - healthchecks and `restart: unless-stopped`.
  - API and Postgres not published.
  - log rotation: `json-file`, max 10 MB, max 5 files.
  - resource limits: API 1 GB, Postgres 1 GB, web 128 MB, cloudflared 128 MB.
  - read-only root filesystems where practical; tmpfs for `/tmp`; media volume writable.
  - non-root containers.
- `infra/compose.staging.yml`:
  - separate project name, volumes, DB, hostnames, tunnel token.
  - same images.
- `infra/cloudflared/config.yml.example` or token-only setup.
- Web serving config is owned by W8 (`web/Dockerfile`, `web/nginx.conf`). S7 consumes the resulting web image in Compose.
  - If W8 is not complete when S7 is implemented, use a temporary static placeholder image to validate the server stack. Do not create a competing nginx config.
  - Required behavior: SPA history fallback, `/api` proxy to `api:8080`, gzip, long cache for hashed assets, `index.html` no-store, 10 MB upload limit, security headers.
- `.dockerignore` for web and server.

### Scripts

- `infra/scripts/build.sh`: build API and web images.
- `infra/scripts/deploy.sh prod|staging`: validate env, build, run migrations via API startup, start services, wait for health, print status.
- `infra/scripts/status.sh`: compose ps + health.
- `infra/scripts/logs.sh`: tail with sane defaults.
- `infra/scripts/backup.sh`:
  - `pg_dump -Fc` to a temp file.
  - tar media volume.
  - restic backup to `RESTIC_REPOSITORY`.
  - restic forget `--keep-daily 7 --keep-weekly 4 --keep-monthly 6`.
  - verify snapshot.
  - remove temp files.
  - non-zero exit on failure.
- `infra/scripts/restore.sh <snapshot>`:
  - stop api/web.
  - restore DB with `pg_restore --clean --if-exists`.
  - restore media.
  - start API, run health check.
  - require explicit `--yes`.
- `infra/scripts/bootstrap-admin.sh`: call the server CLI in the api container.
- `infra/scripts/pair-device.sh`: call the server CLI and print QR.
- `infra/scripts/generate-ca.sh`: generate CA if absent.
- `infra/scripts/check-env.sh`: fail fast on missing/weak values.

### Backups

- Manual schedule by operator (cron/systemd timer on host outside the repo, documented).
- Restic repository: `RESTIC_REPOSITORY`, default `/opt/bethanys-bakery/backups/restic`.
- Optional Cloudflare R2/S3 backend supported by restic env; document.
- Backup includes: database dump, media archive.
- Does not include: `.env`, CA private key, or source. Operator stores secrets separately.
- Retention: 7 daily, 4 weekly, 6 monthly.
- Monthly restore drill required.
- Document expected RPO (last backup) and RTO (under 1 hour for this scale).

### Admin app release metadata

- Implement `getAdminAppInfo` as static configuration; no database table.
- Environment:
  - `ADMIN_APP_LATEST_VERSION`
  - `ADMIN_APP_MIN_VERSION`
  - `ADMIN_APP_DOWNLOAD_URL`
  - `ADMIN_APP_RELEASE_NOTES` (optional)
- The endpoint requires mTLS + `X-Device-Key` but not an admin bearer token, so a paired device can run the update gate before login.
- Return the configured values; cache for 5 minutes.
- Reject requests from unpaired or revoked devices through the normal device filter.

## Observability

- JSON logs with request ID, method, path, status, duration, user type (not PII).
- Docker log rotation configured.
- Actuator readiness checks DB.
- `infra/scripts/status.sh` shows health.
- Disk-space warning documented for media/backups.
- Notification failures visible in admin app.
- Audit log available via API.
- No Prometheus stack.

## Testing and verification

- Full backend test suite on a clean database.
- Test migration path: S1 → S7 on an existing database.
- End-to-end API flow with local providers:
  1. Bootstrap admin.
  2. Pair device.
  3. Create template + media.
  4. Create/publish menu.
  5. Customer OTP login.
  6. Place order.
  7. Accept, mark paid, ready, completed.
  8. Verify notifications logged and order status.
  9. Cancel/expiry paths.
- Load smoke: 50 concurrent order attempts against 10 units; no oversell.
- Manual security checks:
  - OWASP ZAP baseline against staging.
  - `docker scout`/Trivy image scan (choose available tool).
  - `./gradlew dependencyCheckAnalyze` if plugin added.
  - Confirm no secrets in `git log`.
- Backup/restore drill on staging.

## Runbooks (`infra/runbooks/`)

- `deploy.md`
- `rollback.md`
- `backup-restore.md`
- `cloudflare-tunnel.md`
- `cloudflare-cache.md`
- `cloudflare-mtls-ca.md`
- `media-reconciliation.md`
- `device-pairing.md`
- `device-revocation.md`
- `certificate-pinning-rotation.md`
- `mailgun-setup.md`
- `textbee-setup.md`
- `fcm-setup.md`
- `incident-response.md`
- `disk-full.md`
- `database-restore.md`

Each runbook: prerequisites, exact commands, verification, rollback, and who to notify.

## Acceptance criteria

- Given a fresh Alpine host, when `setup-alpine.sh` runs twice, then it is idempotent and leaves secrets mode `600`.
- Given `deploy.sh prod`, when env is valid and the W8 web image is available, then all services become healthy and the customer host serves the web app over HTTPS.
- Given W8 is not complete, when S7 is validated, then the API/Postgres/cloudflared stack is healthy and the web slot can use a placeholder image.
- Given an invalid/missing secret, when `check-env.sh` or API startup runs, then it fails before serving traffic.
- Given a direct request to the origin IP/port from outside, then it is unreachable.
- Given a non-US country header on customer API, then 403 `COUNTRY_BLOCKED`.
- Given a missing country header with `CLOUDFLARE_PROXY=true`, then 403.
- Given a valid admin mTLS cert and device key, then admin API works; without either, 401.
- Given a revoked device, then admin API returns 401 immediately.
- Given a customer mutation from a foreign Origin, then 403 and no CORS headers.
- Given rate limits exceeded, then 429 with `Retry-After`.
- Given a backup run, then a restic snapshot exists and contains the DB dump and media.
- Given a restore run, then the app starts, orders/menus exist, and media loads.
- Given 50 concurrent attempts for 10 units, then exactly 10 orders succeed.
- Given `prod` profile, then OpenAPI UI and local bypass are disabled.
- Given logs, then no OTP/token/email/phone/secret appears.

## Test plan

- Shellcheck setup/scripts.
- Compose config validation for prod/staging.
- Migration-from-previous-phase test.
- Container build and non-root/read-only checks.
- Healthcheck and restart behavior.
- Country/Origin/CSRF/rate-limit integration tests.
- mTLS header parsing and revoked device tests.
- Backup script test against a disposable database.
- Restore drill on staging.
- Manual ZAP baseline and dependency/image scan.
- Load smoke and oversell regression.

## Developer tooling

Gate findings from earlier phases; add these checks and document each command in the relevant README.

- Java formatting/static analysis: add Spotless or Checkstyle (optionally PMD) with a `check` task wired into `./gradlew build`.
- YAML: add `yamllint` config for `docs/api/openapi.yaml` and `infra/*.yml`.
- Dockerfile: run `hadolint` on `server/Dockerfile` and on Dockerfiles added by later phases.
- OpenAPI: validate `docs/api/openapi.yaml` with a spec validator (for example `swagger-cli validate` or `openapi-spec-validator`) so contract drift is caught automatically.
- Coverage: add JaCoCo to `server/build.gradle.kts` with HTML/XML reports and a documented minimum coverage threshold for the critical catalog/media paths.

## Security checklist

- [ ] No inbound host ports.
- [ ] Cloudflare mTLS CA uploaded and verified.
- [ ] US geo rule verified with a non-US probe.
- [ ] HSTS/CSP/security headers verified.
- [ ] Secrets mode 600 and not in git.
- [ ] Prod config rejects local bypass.
- [ ] Rate limits verified.
- [ ] Backups encrypted and restore tested.
- [ ] Logs redacted.
- [ ] Containers non-root.
- [ ] Dependency/image scans reviewed.
- [ ] Device revocation tested.
- [ ] Pin rotation documented.
- [ ] Admin bootstrap and pairing codes single-use.

## Quality gate

This phase is not complete until `/phase-gate S7` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Production compose deploys on Alpine, all acceptance criteria pass, runbooks exist, a backup and restore have been demonstrated, and the full local end-to-end flow succeeds. The full-stack HTTPS acceptance requires the W8 web image; server-only validation may use a placeholder.

## Handoff notes

- After S7 the server is feature-complete; remaining work is web and Android phases.
- Full-stack production verification waits for W8's `web/Dockerfile` and `web/nginx.conf`.
- Operational changes require updating runbooks and `.env.example`.
- Do not add CI/CD unless the owner requests it.
