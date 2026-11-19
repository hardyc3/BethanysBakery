# Manual security / static-analysis checklist

Run before each production deploy and after any dependency, Dockerfile, or API contract change.
All commands are from repo root unless noted.

Static analysis and contract
- [ ] `cd server && ./gradlew check` — Checkstyle (`server/config/checkstyle/checkstyle.xml`), tests, JaCoCo HTML/XML report.
- [ ] `cd server && ./gradlew jacocoTestCoverageVerification` — catalog/order floor 60% line / 50% branch; report `server/build/reports/jacoco/test/html/index.html`.
- [ ] `yamllint -c .yamllint.yml docs/api/openapi.yaml infra/*.yml`
- [ ] `npx @stoplight/spectral-cli lint --ruleset .spectral.yaml docs/api/openapi.yaml` — `operationId` present and unique.
- [ ] `python3 -m openapi_spec_validator docs/api/openapi.yaml`

Secrets
- [ ] `gitleaks detect --config gitleaks.toml --source . --redact --no-banner` — zero findings.
- [ ] `git log -p -S'BEGIN PRIVATE KEY' --all` — no committed key material.
- [ ] `stat -c '%a %n' /opt/bethanys-bakery/{prod,staging}/.env /opt/bethanys-bakery/secrets/*` — all `600`.

Images and dependencies
- [ ] `hadolint -c .hadolint.yml server/Dockerfile web/Dockerfile` (skip `web/Dockerfile` until W8 lands).
- [ ] `docker scout cves bethanys-bakery-api:prod` or `trivy image bethanys-bakery-api:prod` — review critical/high.
- [ ] `cd server && ./gradlew dependencyCheckAnalyze` if the OWASP plugin is present; otherwise review `./gradlew dependencies` against advisories.
- [ ] Explicitly review pinned S3 image libraries: `imageio-webp`, `webp-imageio`, `metadata-extractor`, `thumbnailator`.

Dynamic / edge
- [ ] OWASP ZAP baseline against staging (authenticated where possible):
  ```bash
  mkdir -p zap
  docker run --rm -v "$PWD/zap:/zap/wrk:rw" -t zaproxy/zap-stable \
    zap-baseline.py -t https://staging-bakery.cherry-home.com -r zap-baseline.html
  ```
  Review all `WARN`/`FAIL`; accept only documented false positives with owner sign-off.
- [ ] External origin probe from a non-Cloudflare network: no host port answers; see `cloudflare-tunnel.md`.
- [ ] Country probe: `CF-IPCountry: CA` → 403 `COUNTRY_BLOCKED`; missing country with proxy on → 403.
- [ ] Foreign `Origin` header → 403 and no CORS headers.
- [ ] Rate limits → 429 with `Retry-After`; mTLS absent/revoked → 401.
- [ ] Logs contain no OTP, token, email, phone, secret, or stack trace in prod.

Verification
- Attach command output (or PASS lines) to the phase/deploy record; any critical/high finding blocks deploy.

Rollback
- For a scanner false positive, document the exception in the deploy record with owner approval; never disable a rule globally to unblock.

Notify
- Owner + ops on-call for any critical/high finding; security owner for suspected secret exposure.
