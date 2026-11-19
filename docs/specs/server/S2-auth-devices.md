# S2 — Authentication, Accounts, and Admin Devices

- Status: ready
- Depends on: S1
- Contract: `docs/api/openapi.yaml` → Customer Auth, Customer profile, Admin Auth, Admin Devices, Admin System audit
- Next: S3

## Goal

Implement passwordless customer login, admin password + TOTP login, rotating sessions, one-time device pairing, mTLS device identity, device revocation, and audit events.

## Non-goals

- Item catalog.
- Menus/orders.
- Notification preferences and provider delivery (S6).
- Cloudflare configuration (S7). This phase reads a configured mTLS header and works locally with a bypass.

## Data model — `V2__auth.sql`

Create tables exactly as described in `docs/domain-model.md`:

- `admin_user`
- `admin_device`
- `device_pairing`
- `customer`
- `otp_challenge`
- `refresh_token`
- `audit_event`

Required constraints/indexes:

- Unique `admin_user.email`.
- Unique `admin_device.cert_fingerprint_sha256`, `admin_device.api_key_hash`.
- Unique `device_pairing.code_hash`.
- Partial unique indexes on non-null `customer.email` and `customer.phone_e164`.
- `refresh_token.token_hash` unique.
- `otp_challenge(destination_hash, created_at)`.
- Check constraints for enums and non-negative counters.
- Single admin enforced in service layer plus a partial unique index on `admin_user` where `status='ACTIVE'` if practical.

## Configuration

- `JWT_SECRET`: base64, at least 32 bytes.
- `APP_ENCRYPTION_KEY`: base64 32 bytes for TOTP secrets.
- `ADMIN_DEVICE_CA_CERT_PATH`, `ADMIN_DEVICE_CA_KEY_PATH`.
- `ADMIN_MTLS_HEADER=Cf-Authenticated-Client-Cert`.
- `ADMIN_MTLS_LOCAL_BYPASS=true` only in `local`.
- `ADMIN_ACCESS_TTL=PT15M`, `ADMIN_REFRESH_TTL=P14D`.
- `CUSTOMER_ACCESS_TTL=PT15M`, `CUSTOMER_REFRESH_TTL=P30D`.
- `OTP_TTL=PT10M`, `OTP_MAX_ATTEMPTS=5`, `OTP_RESEND_SECONDS=60`, `OTP_MAX_SENDS_PER_15M=3`.
- `ADMIN_MAX_FAILED_LOGINS=5`, `ADMIN_LOCKOUT=PT15M`.
- `APP_PUBLIC_BASE_URL`, `APP_ADMIN_API_URL`, `APP_PAIR_URL`.
- `ADMIN_APP_LATEST_VERSION`, `ADMIN_APP_MIN_VERSION`, `ADMIN_APP_DOWNLOAD_URL`, `ADMIN_APP_RELEASE_NOTES` (drives `GET /admin/v1/app-info`).

Cookie names:

- `bb_access` — HttpOnly, Secure in non-local, SameSite=Lax, Path=/.
- `bb_refresh` — HttpOnly, Secure in non-local, SameSite=Lax, Path=/api/v1/auth.
- `bb_csrf` — readable by JS, Secure in non-local, SameSite=Lax, Path=/.

## Authentication design

### Customer OTP

- Channels: `EMAIL`, `SMS`.
- Normalize email lowercase; phone to E.164.
- Code: 6 digits, cryptographically random, hashed with a server pepper.
- OTP challenge is single-use and expires in 10 minutes.
- Max 5 verify attempts per challenge and 20 verify attempts per IP per hour.
- Max 3 sends per destination per 15 minutes and 10 per IP per hour.
- Verify creates the customer if absent, marks the used channel verified, and starts a session.
- If the destination belongs to a disabled customer, return the same generic 202 on request and 401 on verify.
- OTP sender interface:
  - `OtpSender.send(channel, destination, code, expiresAt)`.
  - `local` profile: log code to console only.
  - `staging`/`prod`: use the S6 `ProviderOtpSender`, backed by the S6 Mailgun/Textbee providers. Missing credentials still return `503 NOT_CONFIGURED`.
- Do not reveal whether an account exists.

### Sessions

- Access token: signed JWT, HS256, 15 minutes.
  - Claims: `sub`, `typ` (`CUSTOMER` or `ADMIN`), `sid`, `deviceId` for admin, `iat`, `exp`, `iss`, `aud`.
- Refresh token: opaque 256-bit random, stored hashed.
- Rotation:
  - On refresh, atomically mark old token rotated and issue a new token in the same family.
  - Reuse of a rotated token revokes the entire family and returns 401.
- Logout revokes the current family.
- Customer cookies; admin tokens returned in JSON.
- Admin token requires the device to be `ACTIVE`.

### CSRF

- Double-submit: `bb_csrf` cookie and `X-CSRF-Token` header must match on customer mutations.
- `GET /api/v1/auth/csrf` sets the cookie and returns the token.
- Ignore CSRF for `GET`, `HEAD`, `OPTIONS`.
- Reject missing/mismatched token with 403 `CSRF_INVALID`.
- `POST /api/v1/auth/refresh` is exempt because its refresh cookie is scoped to `Path=/api/v1/auth`, marked `SameSite=Lax`, and the endpoint returns no CSRF-sensitive write. OTP request/verify are pre-session endpoints.
- All other customer mutations (`PATCH /api/v1/me`, contact verification, logout) require the double-submit pair.

### Admin device pairing

- CLI `pair-device`:
  - Generates a 128-bit code.
  - Stores only the hash, 15-minute TTL.
  - Prints the code, pair URL, admin API URL, and a terminal QR code.
- `POST /admin/v1/devices/pair` on the pair hostname:
  - Validates code, single use.
  - Validates CSR signature and EC P-256 key.
  - Signs the CSR with the private CA, 180-day certificate.
  - Generates a 256-bit API key, stores only the hash.
  - Creates `admin_device` with the certificate SHA-256 fingerprint.
  - Returns client cert PEM, CA cert PEM, API key, expiry.
  - Audit event `ADMIN_DEVICE_PAIRED`.
- Pair endpoint rate limit: 10 attempts per 15 minutes per IP.

### Admin mTLS filter

- All `/admin/v1/**` endpoints except pairing require:
  - Cloudflare-verified mTLS client certificate forwarded by Cloudflare.
  - `X-Device-Key` header.
  - Bearer access token for authenticated operations.
- First require Cloudflare verification header `Cf-Authenticated-Client-Cert-Verified: true` (or configured equivalent). If missing/unverified/false → 401. Do not trust the certificate header alone.
- Then read the configured PEM header, compute SHA-256 of the DER certificate, and match an `ACTIVE` device.
- Reject missing header, invalid PEM, unknown fingerprint, or revoked device with 401.
- API key compared in constant time to stored hash.
- Local bypass only when profile is `local` and `ADMIN_MTLS_LOCAL_BYPASS=true`; bypass still requires `X-Device-Key` and explicit bypass flag. Never in prod/staging.
- Update `last_seen_at` and `app_version` from optional `X-App-Version` at most once per minute.

### Device revocation

- `DELETE /admin/v1/devices/{deviceId}` sets `status=REVOKED`, `revoked_at`, and clears `fcm_token` once the S6 column exists.
- Revoke every non-revoked refresh token for that `device_id` in the same transaction.
- Subsequent mTLS/API-key requests fail with 401 immediately; any access token remains unusable because the device filter runs first.
- Revoking the current device is allowed; the app must clear local credentials and re-pair.
- Audit `ADMIN_DEVICE_REVOKED` with the acting admin and device ID.

### Admin bootstrap CLI

- `bootstrap-admin --email --name` prompts for a password or reads `ADMIN_BOOTSTRAP_PASSWORD`.
- Rejects if an active admin exists.
- Argon2id password hash.
- Generates TOTP secret, prints `otpauth://` URI and terminal QR.
- Generates 10 recovery codes, stores hashes, prints once.
- Enforces TOTP enabled.
- Audit event `ADMIN_BOOTSTRAPPED`.

### Admin login

- Requires device identity via mTLS + API key.
- Email + password + TOTP or recovery code.
- Lockout after 5 failed attempts for 15 minutes.
- Generic 401 message on failure.
- Recovery code is single use.
- Returns admin session with access/refresh tokens.
- Audit `ADMIN_LOGIN_SUCCESS` / `ADMIN_LOGIN_FAILED`.

### Customer profile

- `GET /api/v1/me`.
- `PATCH /api/v1/me`: name; email/phone only through verification flow.
- Contact verification:
  - Request sends OTP to the new destination.
  - Confirm verifies and updates the customer, rejecting destinations already used by another active customer.
  - On verified contact change, revoke all other customer refresh token families (keep current session family) to limit account takeover persistence.
- Audit customer contact changes as `CUSTOMER_CONTACT_CHANGED`.

### Audit

- `audit_event` append-only.
- Record actor, action, entity, redacted before/after JSON, request ID.
- Redact passwords, tokens, OTPs, TOTP secrets, API keys, full contact values where not needed.
- `GET /admin/v1/audit-events` with optional entity filter and pagination.

## API operations

Implement and match OpenAPI:

- `getCsrfToken`
- `requestCustomerOtp`
- `verifyCustomerOtp`
- `refreshCustomerSession`
- `logoutCustomer`
- `getCustomerProfile`
- `updateCustomerProfile`
- `requestContactVerification`
- `confirmContactVerification`
- `pairAdminDevice`
- `loginAdmin`
- `refreshAdminSession`
- `logoutAdmin`
- `getAdminProfile`
- `listAdminDevices`
- `revokeAdminDevice`
- `listAuditEvents`
- `getAdminAppInfo` (contract completeness; device-authenticated, no bearer required)

## Ordered tasks

1. Add `V2__auth.sql` and JPA entities/repositories.
2. Add crypto utilities: Argon2id, TOTP, AES-GCM, token hashing, constant-time compare.
3. Add customer OTP request/verify, rate limiting, and OTP sender interface.
4. Add JWT access tokens, refresh rotation/reuse detection, cookies, CSRF.
5. Add customer profile and contact verification.
6. Add admin bootstrap and pairing CLIs with CA signing.
7. Add mTLS header + device API key filters.
8. Add admin login, TOTP/recovery, lockout, sessions.
9. Add device list/revoke and audit endpoints.
10. Add tests and update docs/env example.
11. Add local test helpers and seeded customer/admin fixtures for later UI phases.

## Acceptance criteria

- Given a new email, when OTP is requested and verified with the logged local code, then a customer is created and cookies are set.
- Given an expired or reused OTP, when verified, then 400 `OTP_INVALID` or `OTP_EXPIRED` and no session.
- Given 6 wrong OTP attempts, when verifying, then the challenge is locked.
- Given 4 OTP requests in 15 minutes for one destination, when requesting again, then 429 `RATE_LIMITED`.
- Given a valid refresh cookie, when refreshing, then a new access and refresh token are issued and the old refresh token cannot be reused.
- Given a reused rotated refresh token, then the family is revoked and 401 returned.
- Given a customer mutation without `X-CSRF-Token`, then 403 `CSRF_INVALID`.
- Given the pairing CLI code, when a valid CSR is posted to pair, then a client certificate and API key are returned once and the code cannot be reused.
- Given a revoked device, when calling an admin endpoint, then 401 even with a valid access token, and its refresh tokens are revoked; S6 also clears its FCM token.
- Given valid credentials and TOTP, when logging in, then an admin session is returned and an audit event exists.
- Given 5 failed admin logins, then the account is locked for 15 minutes.
- Given a recovery code, when used once, then login succeeds and the same code fails afterward.
- Given a contact change, when the OTP is confirmed, then the profile is updated and no other account owns the destination.

## Test plan

- Unit: TOTP vectors, Argon2id verify, AES-GCM round trip, token hashing, rate limiter.
- Integration: OTP happy path, expiry, attempts, throttling, enumeration resistance.
- Integration: refresh rotation and reuse detection.
- Integration: CSRF accept/reject.
- Integration: pairing CSR, single-use code, revoked device, invalid API key.
- Integration: admin login success/failure/lockout/recovery, TOTP window tolerance.
- Integration: profile update and contact conflicts.
- Security: cookie flags in prod profile, no secrets in logs, generic auth errors.

## Security checklist

- [ ] OTP codes and refresh tokens stored hashed.
- [ ] API keys stored hashed, compared constant time.
- [ ] TOTP secrets encrypted at rest.
- [ ] No account-existence leak.
- [ ] Pair code single-use and rate limited.
- [ ] Device revocation immediate.
- [ ] Admin lockout.
- [ ] CSRF on customer mutations.
- [ ] Cookies HttpOnly/Secure/SameSite in prod.
- [ ] Audit events for auth and device changes.
- [ ] Logs redact PII and secrets.

## Quality gate

This phase is not complete until `/phase-gate S2` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Quality gate results (2026-11-16)

| Agent | Status | Findings | Action taken |
|---|---|---|---|
| spec-compliance | PASS | All 17 contract operations implemented; `getAdminAppInfo` was missing from the spec's operation list; `admin_user.name` and `otp_challenge.purpose=CONTACT_VERIFY` were missing from the domain model. | Added `getAdminAppInfo` + `ADMIN_APP_*` config and CSRF scope notes to this spec; updated `docs/domain-model.md`. |
| lint | PASS (no dedicated linter configured) | `./gradlew check` runs only tests (no Checkstyle/Spotless/PMD). Secret scan found only committed local/test dev secrets. No TODO/FIXME or debug output. | Recorded the missing lint tooling as an S7/foundation follow-up below. |
| refactor | PASS | Pairing route ran customer auth unnecessarily; X.500 CN accepted newlines; one fully-qualified `Optional`. | Filter now skips both auth paths for `/admin/v1/devices/pair`; CN restricted to a safe charset; import cleaned. |
| test-quality | PASS | 53 tests, 0 failures (unit crypto/session/rate limit/cookies + OTP, refresh, CSRF, pairing, revocation, lockout, recovery, profile, audit integration). Added SMS normalization and audit redaction/list coverage. | All acceptance criteria have automated coverage. |
| security | PASS | Security checklist met. Warnings: in-memory per-IP rate limiter is per-instance; dev compose relies on local-profile auth defaults. | Warnings recorded in handoff notes for S7. |
| gap-analysis | PASS | Spec drift fixed (app-info, domain fields, CSRF scope). No new phase spec required. | Updates made to `docs/specs/server/S2-auth-devices.md`, `docs/domain-model.md`, `server/README.md`, `infra/.env.example`. |

## Definition of done

All acceptance criteria pass, OpenAPI matches, migrations apply cleanly, local bootstrap/pairing CLIs documented, and A2/W2 can build against the auth contract.

## Handoff notes

- S6 implements real OTP email/SMS senders. S2's local logger is the test path.
- Cloudflare mTLS CA upload and header configuration are S7.
- Notification preferences are S6; W7 depends on S6.
- Admin app must pair before login.
- The per-IP in-memory rate limiter is per-instance; S7 (or a shared cache) should make it cluster-wide before production scale.
- `infra/compose.dev.yml` relies on the committed local-profile defaults for auth secrets and the local mTLS bypass; S7 must inject real `JWT_SECRET`/`APP_ENCRYPTION_KEY` from deployment secrets and disable the bypass in staging/prod.
- Lint tooling (Checkstyle/Spotless/PMD) is not configured in the server build yet; `./gradlew check` currently runs only tests. Add a lint task in S7 hardening or a foundation follow-up.
- `ADMIN_BOOTSTRAP_PASSWORD` and the bootstrap recovery codes are only printed once; the operator must store them outside the repo.
