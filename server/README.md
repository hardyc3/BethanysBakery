# Bethany's Bakery API

## Prerequisites
- Java 21
- Docker + Docker Compose (for local stack / Testcontainers)

## Run tests
```bash
cd server
./gradlew test
```
Unit tests cover crypto (Argon2id, TOTP, AES-GCM, hashing, rate limiting) and cookie flags.
Integration tests use Testcontainers and cover OTP, sessions, CSRF, pairing, revocation, admin login/lockout/recovery, profile/contact changes, and audit.

## Run local stack
```bash
cd infra
docker compose -f compose.dev.yml up --build
# or: ./scripts/dev.sh
```
API: http://localhost:8080/api/v1/config

## Environment variables
See `../infra/.env.example`.
- Database: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_MAX`, `DB_POOL_MIN`
- Bakery: `BAKERY_NAME`, `BAKERY_TIMEZONE`, `BAKERY_PICKUP_ADDRESS`, `BAKERY_MAPS_URL`, `BAKERY_SUPPORT_EMAIL`, `ORDER_CUTOFF_HOURS`
- Auth crypto: `JWT_SECRET`, `APP_ENCRYPTION_KEY` (base64, at least 32 bytes)
- Device CA: `ADMIN_DEVICE_CA_CERT_PATH`, `ADMIN_DEVICE_CA_KEY_PATH`
- mTLS: `ADMIN_MTLS_HEADER`, `ADMIN_MTLS_VERIFIED_HEADER`, `ADMIN_MTLS_LOCAL_BYPASS`
- Sessions/OTP: `ADMIN_ACCESS_TTL`, `ADMIN_REFRESH_TTL`, `CUSTOMER_ACCESS_TTL`, `CUSTOMER_REFRESH_TTL`,
  `OTP_TTL`, `OTP_MAX_ATTEMPTS`, `OTP_RESEND_SECONDS`, `OTP_MAX_SENDS_PER_15M`,
  `ADMIN_MAX_FAILED_LOGINS`, `ADMIN_LOCKOUT`
- Links/app info: `APP_PUBLIC_BASE_URL`, `APP_ADMIN_API_URL`, `APP_PAIR_URL`,
  `ADMIN_APP_LATEST_VERSION`, `ADMIN_APP_MIN_VERSION`, `ADMIN_APP_DOWNLOAD_URL`, `ADMIN_APP_RELEASE_NOTES`

Generate secrets:
```bash
openssl rand -base64 32   # JWT_SECRET
openssl rand -base64 32   # APP_ENCRYPTION_KEY
```

## Auth overview (S2)
- Customer login is passwordless OTP (`EMAIL` / `SMS`).
  - `POST /api/v1/auth/otp/request` → 202 (generic response whether or not the account exists).
  - `POST /api/v1/auth/otp/verify` → sets `bb_access` and `bb_refresh` cookies.
  - Local/test profiles log the code to the server console and never send real messages.
  - Staging/prod return `503 NOT_CONFIGURED` for OTP delivery until S6 wires providers.
- Customer mutations require the double-submit CSRF pair: `bb_csrf` cookie + `X-CSRF-Token` header.
  Fetch it with `GET /api/v1/auth/csrf`.
- Access tokens are HS256 JWTs (15 min default). Refresh tokens are opaque, rotated on use, and reuse revokes the family.
- Admin endpoints require a paired device (`X-Device-Key`) plus a bearer token.
  - In `local`/`test`, `ADMIN_MTLS_LOCAL_BYPASS=true` skips the Cloudflare certificate headers.
  - In staging/prod, the Cloudflare-verified client certificate header is required and its SHA-256 fingerprint must match an `ACTIVE` device.
- Revoking a device is immediate: its API key and certificate stop authenticating and all its refresh tokens are revoked.

## Bootstrap the admin (first run)
The CLI runs without starting the web server. It needs the same database and crypto env vars as the API.

```bash
cd server
ADMIN_BOOTSTRAP_PASSWORD='choose-a-long-password' \
  ./gradlew bootRun --args='--spring.profiles.active=local --bootstrap-admin --email admin@example.com --name "Bethany"'
```

- Rejected if an active admin already exists.
- Password: Argon2id. Prompted when `ADMIN_BOOTSTRAP_PASSWORD` is unset and a terminal is available.
- Prints the `otpauth://` URI, a terminal QR code, and 10 single-use recovery codes exactly once.
- Writes an `ADMIN_BOOTSTRAPPED` audit event.

## Pair an Android device
```bash
cd server
./gradlew bootRun --args='--spring.profiles.active=local --pair-device'
```

- Prints a 128-bit one-time code (hash stored, 15-minute TTL), pair URL, admin API URL, and a terminal QR code.
- The app posts the code, device name, public key PEM, and CSR PEM to `POST /admin/v1/devices/pair`.
- The server validates the CSR (EC P-256), signs a 180-day client certificate with the device CA, and returns the certificate, CA certificate, and a 256-bit API key once.
- In `local`/`test`, an ephemeral device CA is generated if no CA files are configured. Staging/prod require `ADMIN_DEVICE_CA_CERT_PATH` and `ADMIN_DEVICE_CA_KEY_PATH`.

## Package layout
- `config` — configuration properties and web config
- `common.error` — RFC 9457 problem handling
- `common.web` — request ID, client IP helpers
- `security` — Spring Security rules and problem responses
- `auth.crypto` — Argon2id, TOTP, AES-GCM, QR rendering
- `auth.otp` — OTP issuance/verification, senders, normalization, rate limiting
- `auth.session` — JWT, refresh rotation, cookies, CSRF, request authentication filter
- `auth.device` — device CA, pairing, mTLS/device-key authentication
- `auth.customer` / `auth.admin` — customer and admin auth/profile services
- `auth.audit` — append-only audit writes and queries
- `domain.auth` / `domain.audit` — entities and repositories
- `api.auth` — S2 controllers and DTOs
- `cli` — `bootstrap-admin` and `pair-device` commands
- `api.publicapi` — public endpoints (`GET /api/v1/config`)
