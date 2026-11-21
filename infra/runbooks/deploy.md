# Deploy (prod / staging)

Prerequisites
- Root/sudo access on the Alpine host; repo checked out at `/opt/bethanys-bakery`.
- `infra/setup-alpine.sh` already run twice with no diffs in `/opt/bethanys-bakery/{prod,staging}/.env`.
- `CLOUDFLARE_TUNNEL_TOKEN` (prod) / `CLOUDFLARE_TUNNEL_TOKEN_STAGING` set; device CA uploaded per `cloudflare-mtls-ca.md`.
- Provider secrets filled; a fresh backup exists (see `backup-restore.md`).
- No open security/scan findings (see `security-scanning.md`).

Commands (replace `prod` with `staging` as needed)
```bash
cd /opt/bethanys-bakery
ENV=prod

git rev-parse HEAD > "infra/PREVIOUS_DEPLOYED_COMMIT.$(date -u +%Y%m%dT%H%M%SZ)"
sh infra/scripts/check-env.sh "$ENV"
sh infra/scripts/backup.sh "$ENV"
sh infra/scripts/deploy.sh "$ENV"
sh infra/scripts/status.sh "$ENV"
docker compose -f "infra/compose.$ENV.yml" --env-file "/opt/bethanys-bakery/$ENV/.env" \
  exec -T api curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

Pre-deploy manual checks (run from repo root)
```bash
(cd server && ./gradlew check)                    # Checkstyle + tests + JaCoCo report
(cd server && ./gradlew jacocoTestCoverageVerification)  # catalog/order floor: 60% line, 50% branch
yamllint -c .yamllint.yml docs/api/openapi.yaml infra/*.yml
npx @stoplight/spectral-cli lint --ruleset .spectral.yaml docs/api/openapi.yaml
python3 -m openapi_spec_validator docs/api/openapi.yaml
hadolint -c .hadolint.yml server/Dockerfile web/Dockerfile 2>/dev/null || :
gitleaks detect --config gitleaks.toml --source . --redact --no-banner
```
Full checklist, image/dependency scans, and ZAP baseline: `security-scanning.md`.

Verification
```bash
curl -fsS https://bakery.cherry-home.com/api/v1/config
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H 'CF-IPCountry: CA' https://bakery.cherry-home.com/api/v1/config          # 403 COUNTRY_BLOCKED
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H 'CF-IPCountry: US' -H 'Origin: https://evil.example' \
  https://bakery.cherry-home.com/api/v1/auth/csrf                              # 403, no CORS
sh infra/scripts/status.sh "$ENV"                                              # all 4 services healthy
docker compose -f "infra/compose.$ENV.yml" --env-file "/opt/bethanys-bakery/$ENV/.env" \
  ps --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0|::' || echo 'no published ports'
```
- Admin API without device cert/key returns 401; with valid cert/key `/admin/v1/app-info` returns 200.
- External-network origin probe in `cloudflare-tunnel.md` must show origin unreachable.
- Logs contain no OTP/token/email/phone/secret.

S5 follow-up verification
- [ ] 50 concurrent order attempts against 10 units → exactly 10 orders, no oversell.
- [ ] Expiry test uses injected fixed `Clock`.
- [ ] Whole-menu cancellation propagation tested (not only one date).
- [ ] Concurrent same-key idempotency replay tested.
- [ ] Duplicate-line merge (same offering + sorted options + identical notes) tested.
- [ ] Blank customer name, unverified contact, merged quantity > 99, notes caps tested.
- [ ] Bake list excludes `REJECTED` and `EXPIRED` explicitly.
- [ ] Outbox row uses `available_at = now` and key `order:{orderId}:created`.
- [ ] Production rate limiter replaced S5 in-memory limiter; single-instance assumption documented.
- [ ] Unexpected 500 stack traces gated by profile; audit free-text `reason`/`notes` redaction enforced.

Rollback
- Follow `rollback.md`. Flyway migrations are forward-only; do not hand-edit schema versions.

Notify
- Owner + ops on-call in status channel before start and after verification; for customer-impacting failures also send to `ADMIN_ALERT_EMAIL`.
