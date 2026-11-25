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
Uploaded images persist in the compose volume `media-data` mounted at `/data/media`. Server and proxy must both enforce the 10 MB upload limit.

## Environment variables
See `../infra/.env.example`.
- Database: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_MAX`, `DB_POOL_MIN`
- Bakery: `BAKERY_NAME`, `BAKERY_TIMEZONE`, `BAKERY_PICKUP_ADDRESS`, `BAKERY_MAPS_URL`, `BAKERY_SUPPORT_EMAIL`, `ORDER_CUTOFF_HOURS`
- Metrics: `METRICS_PORT` (default 9090), `METRICS_BIND_ADDRESS` (default 0.0.0.0; firewall allowlist via `PROMETHEUS_SCRAPE_CIDR`)
- Media: `MEDIA_ROOT` (default `/data/media`), `MEDIA_MAX_UPLOAD_BYTES` (10485760 = 10 MB), `MEDIA_MAX_REQUEST_BYTES` (11534336 = 11 MB), `MEDIA_MAX_EDGE_PIXELS` (1600), `MEDIA_MAX_DECODE_PIXELS` (40000000), `MEDIA_MAX_SOURCE_EDGE` (10000)
- Auth crypto: `JWT_SECRET`, `APP_ENCRYPTION_KEY` (base64, at least 32 bytes)
- Device CA: `ADMIN_DEVICE_CA_CERT_PATH`, `ADMIN_DEVICE_CA_KEY_PATH`
- mTLS: `ADMIN_MTLS_HEADER`, `ADMIN_MTLS_VERIFIED_HEADER`, `ADMIN_MTLS_LOCAL_BYPASS`
- Sessions/OTP: `ADMIN_ACCESS_TTL`, `ADMIN_REFRESH_TTL`, `CUSTOMER_ACCESS_TTL`, `CUSTOMER_REFRESH_TTL`,
  `OTP_TTL`, `OTP_MAX_ATTEMPTS`, `OTP_RESEND_SECONDS`, `OTP_MAX_SENDS_PER_15M`,
  `ADMIN_MAX_FAILED_LOGINS`, `ADMIN_LOCKOUT`
- Links/app info: `APP_PUBLIC_BASE_URL`, `APP_ADMIN_API_URL`, `APP_PAIR_URL`,
  `ADMIN_APP_LATEST_VERSION`, `ADMIN_APP_MIN_VERSION`, `ADMIN_APP_DOWNLOAD_URL`, `ADMIN_APP_RELEASE_NOTES`
- Admin alerts (S8): `ADMIN_ALERT_EMAIL` (always), `ADMIN_ALERT_PHONE` (optional SMS). Full destinations are masked in API responses and logs; store them only in the mode-600 `.env` file. No FCM variables remain.
- Providers: `MAILGUN_API_KEY`, `MAILGUN_DOMAIN`, `MAILGUN_API_BASE`, `MAILGUN_FROM`, `TEXTBEE_BASE_URL`, `TEXTBEE_API_KEY`, `TEXTBEE_DEVICE_ID`, `TEXTBEE_WEBHOOK_SECRET`

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
  - Dummy profile always uses OTP `123456`; no provider is contacted.
  - Staging/prod use Mailgun/Textbee providers.
- Customer mutations require the double-submit CSRF pair: `bb_csrf` cookie + `X-CSRF-Token` header.
  Fetch it with `GET /api/v1/auth/csrf`.
- Access tokens are HS256 JWTs (15 min default). Refresh tokens are opaque, rotated on use, and reuse revokes the family.
- Admin endpoints require a paired device (`X-Device-Key`) plus a bearer token.
  - In `local`/`test`/`dummy`, `ADMIN_MTLS_LOCAL_BYPASS=true` skips the Cloudflare certificate headers.
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
- In `local`/`test`/`dummy`, an ephemeral device CA is generated if no CA files are configured. Staging/prod require `ADMIN_DEVICE_CA_CERT_PATH` and `ADMIN_DEVICE_CA_KEY_PATH`.

## Observability and admin preview (S9)
- Actuator and Prometheus move to `METRICS_PORT` (default 9090), separate from API 8080.
  - Readiness: `GET http://127.0.0.1:9090/actuator/health/readiness`
  - Scrape: `GET http://127.0.0.1:9090/actuator/prometheus`
  - No app auth and no Cloudflare route: production restricts tcp/9090 with `PROMETHEUS_SCRAPE_CIDR`.
  - Seven unlabeled counters: `bakery_orders_{created,paid,cancelled,ready,delayed,rejected,expired}_total`. Only committed state transitions increment; replays, conflicts, and no-ops do not.
- `POST /admin/v1/notifications/recipient-preview?page=0&size=20` is admin-only and read-only.
  - Body: `eventType`, `entityType` (`MENU`/`MENU_DATE`/`ORDER`), `entityId`.
  - Response uses the same `NotificationAudienceResolver` as enqueue, with masked contacts, channel counts, reasons, and stable `(name, customerId)` pagination. It writes no notification, outbox, or audit rows.

## Dummy server image (S9)
Build locally from the `server` directory; no registry is involved.

```bash
cd server
docker build --target dummy -t bethanys-bakery-dummy .
docker run --rm -p 127.0.0.1:8080:8080 -p 127.0.0.1:9090:9090 bethanys-bakery-dummy
```

Startup: embedded PostgreSQL starts in the container, Flyway applies V1–V8, fixed admin/device rows are seeded, then API 8080 and management 9090 become ready (allow 2 minutes). No DB port is published and no DB volume is mounted; stopping the container discards all state. No Mailgun/Textbee/FCM credentials are needed and no provider is contacted.

Fixed constants (non-production only; never use with `prod`/`staging`):

| Item | Value |
|---|---|
| Admin email | `admin@dummy.local` |
| Admin password | `DummyAdmin!234` |
| Admin TOTP secret | `JBSWY3DPEHPK3PXP` |
| Admin recovery code | `DUMMY-12345` |
| Admin device key | `dummy-device-key` |
| Customer OTP | `123456` |

API-only seeding flow for web/Android functional tests:

1. `GET /api/v1/auth/csrf`; send `bb_csrf` cookie and `X-CSRF-Token` on customer mutations.
2. `POST /api/v1/auth/otp/request` then `/otp/verify` with `123456` to create/sign in a customer.
3. `POST /admin/v1/auth/login` with the fixed email/password, a current TOTP from the fixed secret, and `X-Device-Key: dummy-device-key`; no pairing flow is needed.
4. Create menus, dates, offerings, and orders through the real API only. Never connect to the embedded database.

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
- `domain.auth` / `domain.audit` / `domain.catalog` — entities and repositories
- `api.auth` — S2 controllers and DTOs
- `api.catalog` / `catalog` — S3 template/media controllers, DTOs, image pipeline and storage
- `cli` — `bootstrap-admin` and `pair-device` commands
- `api.publicapi` — public endpoints (`GET /api/v1/config`)
