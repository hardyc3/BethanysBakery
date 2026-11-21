# Bethany's Bakery — Architecture

Status: approved baseline  
Last updated: 2026-11-20

## 1. Context

Bethany runs a home bakery. She publishes a weekly menu, limits how many of each item she will bake per day, takes orders, collects Venmo payments manually, and notifies customers when orders are ready.

The system replaces emailed/texted order forms.

Constraints:

- One admin: Bethany.
- Fewer than 50 customers.
- About 50 orders per week.
- About 10 concurrent users at peak.
- About 20 SMS messages on a baking day.
- Self-hosted on Proxmox.
- Alpine Linux host.
- Public ingress through Cloudflare Tunnel.
- Bakery timezone: `America/Denver`.
- Pickup location: Lehi, Utah.
- Manual builds, deployments, and backups. No CI/CD pipeline required.
- Docker + Docker Compose for all runtime services.

## 2. Goals

- Admin creates menus, bake dates, items, prices, quantities, pickup windows, and photos from an Android app.
- Customers browse the published menu, see live availability, customize items, choose a pickup window, and place orders from a mobile web app.
- Inventory is enforced atomically. Sold-out items cannot be ordered. Customers are notified in-app via live availability/sold-out state and clear UI messaging (no separate out-of-band sold-out message required).
- Admin accepts or rejects orders, marks payments, triggers ready/delay/cancel notifications, and views a bake list.
- Customers receive email/SMS notifications for menu publication, reminders, acceptance, ready orders, delays, rejections, and cancellations.
- Only approved Android admin devices can call admin APIs.
- Customer API is restricted to the customer web client and US traffic on a best-effort basis.

## 3. Non-goals

- Automated Venmo verification or refunds.
- Multiple bakeries or multi-tenant operation.
- iOS admin app.
- Native customer app.
- Delivery.
- Tax calculation.
- Complex accounting.
- Online card payments.
- Realtime websockets. Clients poll order status.
- Kubernetes.
- Horizontal scaling. Single API instance is expected.

## 4. System context

```text
                    ┌───────────────────────────────────────┐
                    │              Cloudflare               │
                    │  TLS, WAF, geo, path-scoped mTLS      │
                    └──────────────────┬────────────────────┘
                                       │ Cloudflare Tunnel (outbound only)
                                       │ one hostname: bakery.cherry-home.com
                     ┌─────────────────┼─────────────────┐
                /admin/v1             /api               /
                     │                 │                 │
                     ▼                 ▼                 ▼
              ┌─────────────────────────────┐   ┌──────────────────┐
              │ api (Spring Boot)           │   │ web (nginx / W8) │
              │ customer API + admin API    │   │ React SPA        │
              │ schedulers + notify worker  │   └──────────────────┘
              └──────┬──────────┬───────────┘
                     │          │
              ┌──────▼─────┐ ┌──▼──────────┐
              │ PostgreSQL │ │ media volume│
              └────────────┘ └─────────────┘
                     │
              ┌──────▼──────────────────┐
              │ Mailgun (email),        │
              │ Textbee (SMS, optional)  │
              └─────────────────────────┘
```

The single Proxmox LXC runs all four containers. Cloudflare WAF/mTLS rules match
request paths, not hostnames; `EdgeRequestFilter` stays path-based and no
hostname is hardcoded in application code.

## 5. Stack

### Server

- Java 21
- Spring Boot 3.5
- Gradle Kotlin DSL
- Spring Security
- Spring Data JPA
- Flyway
- PostgreSQL 16
- springdoc OpenAPI
- Jakarta Validation
- Testcontainers, JUnit 5
- Actuator health
- Runtime image: `eclipse-temurin:21-jre` (glibc required by the `webp-imageio` WebP writer JNI library; the build stage stays Alpine)

### Customer web

- React 19
- Vite
- TypeScript strict
- React Router
- TanStack Query
- Zod
- Tailwind CSS
- Vitest, React Testing Library, Playwright
- PWA manifest and app shell. No offline ordering.
- Served by nginx; nginx proxies `/api` to the API container.

### Admin Android

- Kotlin
- Jetpack Compose
- Hilt
- Retrofit + OkHttp
- Kotlin serialization
- Android Photo Picker / CameraX
- QR scanning
- Android Keystore for device key, client certificate, API key, tokens
- Min SDK 26
- Sideloaded APK
- In-app version check
- JUnit, MockWebServer, Compose UI tests

### Infrastructure

- Alpine Linux Proxmox LXC (one production container)
- Docker Engine
- Docker Compose plugin
- Cloudflare Tunnel (`cloudflared`)
- PostgreSQL container
- nginx container for the web app
- Proxmox full-container backups (no in-repo backup automation)
- Private CA for admin device client certificates
- Cloudflare-managed HTTPS certificates (no origin cert)

## 6. Repository layout

```text
server/
  build.gradle.kts
  src/main/java/...
  src/main/resources/db/migration/
  src/test/...
web/
  package.json
  src/...
  e2e/...
  Dockerfile
  nginx.conf
android-admin/
  settings.gradle.kts
  app/src/main/...
infra/
  compose.dev.yml
  compose.prod.yml
  setup-alpine.sh
  .env.example
  cloudflared/
  scripts/          # generate-ca, pair-device, bootstrap-admin, status, logs
  runbooks/
docs/
  initial_project_description.md
  architecture.md
  domain-model.md
  api/openapi.yaml
  specs/
    README.md
    server/S1..S8
    web/W1..W8
    android/A1..A8
```

## 7. Hostnames

One public hostname: `bakery.cherry-home.com` (placeholder for the owner's
domain). Production values live in `/opt/bethanys-bakery/.env`. There is no
staging hostname and no pair/admin-api hostname.

| Path on `bakery.cherry-home.com` | Tunnel service | Protection |
|---|---|---|
| `/admin/v1` | `api:8080` | Cloudflare mTLS on `/admin/v1/**` except `/admin/v1/devices/pair`; device API key at origin |
| `/api` | `api:8080` | Cloudflare WAF, US-only geo rule on `/api/v1/**` (webhooks exempt) |
| `/` | `web:80` | Cloudflare WAF; W8 SPA |

`/admin/v1/devices/pair` is mTLS-exempt, rate limited at the edge, and returns
409 `INVALID_STATE` if a device is already active. `/api/v1/webhooks/textbee`
is unauthenticated at the edge and HMAC-signature-verified at the origin.
Cloudflare Tunnel is the only ingress; no inbound ports on the Proxmox host.
Cloudflare handles HTTPS certificates. No hostname is hardcoded in app code;
`EdgeRequestFilter` matches paths only.

## 8. Docker Compose topology

All services run in one Alpine Proxmox LXC via `infra/compose.prod.yml`:

- `web`: nginx (W8 image; `httpd:2.4-alpine` placeholder until then), serves the
  React build. Its `/api` proxy is for local development only — production
  ingress routes `/api` directly to `api:8080`.
- `api`: Spring Boot, exposes `8080` only on the internal Docker network.
- `postgres`: PostgreSQL 16, internal network only, named volume.
- `cloudflared`: outbound tunnel, configured from `CLOUDFLARE_TUNNEL_TOKEN`.
- No backup profile container and no staging project.

Networks:

- `edge`: web, api, cloudflared.
- `backend`: api, postgres.

Volumes (inside the LXC, included in Proxmox container backups):

- `postgres-data`
- `media-data`

Healthchecks:

- `api`: `GET /actuator/health/readiness`
- `postgres`: `pg_isready`
- `web`: `GET /`
- `cloudflared`: process check

Startup order: postgres healthy → api healthy → web healthy → cloudflared.
Flyway migrations run during API startup. One API replica only.

## 9. Deployment strategy

Manual, no CI/CD and no registry. One production environment on one LXC.

1. `infra/setup-alpine.sh` prepares the LXC once:
   - installs `docker`, `docker-cli-compose`, `git`, `openssl`, `curl`, `jq`
   - enables Docker
   - creates `/opt/bethanys-bakery` and `/opt/bethanys-bakery/secrets`
   - creates the service user and permissions
   - generates the admin device CA if missing
   - writes `/opt/bethanys-bakery/.env` from the example with strong secrets
   - does not start the stack
2. Operator fills the tunnel token, provider secrets, `ADMIN_ALERT_EMAIL`, and
   optional `ADMIN_ALERT_PHONE` in that `.env`, then uploads the device CA to
   Cloudflare. Local development uses `infra/compose.dev.yml`.
3. Deploy from the checkout inside the LXC:
   ```bash
   cd /opt/bethanys-bakery
   git pull --ff-only
   docker compose -f infra/compose.prod.yml --env-file /opt/bethanys-bakery/.env up -d --build
   ```
4. `infra/scripts/status.sh` reports health. There is no build/deploy/check-env
   wrapper; the raw commands above are the deployment procedure.

Backups are Proxmox full-container backups only. Owner verifies that a snapshot
contains the Docker volumes, `.env`, and the device CA key, and keeps off-repo
copies of the secrets. No pg_dump, restic, offsite copy, or restore script is
shipped here. Rollback is code-first (check out the previous commit and re-run
the Compose command); data recovery is a Proxmox snapshot restore. Flyway
migrations are forward-only.

## 10. Secrets and configuration

- Local development uses `infra/.env.example` with local-safe defaults.
- Production secrets live in `/opt/bethanys-bakery/.env`, mode `600`, never in git.
- `infra/.env.example` documents every variable.
- Admin CA private key lives in `/opt/bethanys-bakery/secrets/device-ca.key`, mode `600`, never in git.
- Spring Boot reads environment variables. No secrets in application YAML.
- Tokens, OTP codes, API keys, passwords, and full contact details are redacted from logs.
- Database password and app encryption key are generated by setup and stored only in `.env`.
- Cloudflare tunnel token is stored only in `.env`.
- `ADMIN_ALERT_EMAIL` and `ADMIN_ALERT_PHONE` are masked in API responses and logs; full values only in `.env`.

Configuration groups:

- `APP_*`: public base URLs, environment name, bakery name.
- `BAKERY_*`: timezone, address, maps URL, Venmo handle, support email.
- `DB_*`: PostgreSQL connection.
- `JWT_*`: signing keys, access/refresh TTLs.
- `OTP_*`: length, TTL, attempts, rate limits.
- `MAILGUN_*`, `TEXTBEE_*`: email and SMS providers.
- `ADMIN_*`: admin alert email/phone, app version info.
- `CLOUDFLARE_*`: tunnel token, mTLS header name, country header.
- `MEDIA_*`: media root, 10 MB upload cap, max decode/edge bounds. No secrets.

## 11. Security model

### Transport

- Public traffic: HTTPS at Cloudflare edge.
- Origin: HTTP inside the Docker network only.
- HSTS at Cloudflare.
- Direct origin access impossible from the public internet.

### Customer web

- Same-origin SPA and API.
- OTP login by email or SMS.
- Access token 15 minutes, refresh token 30 days, both in `HttpOnly`, `Secure`, `SameSite=Lax` cookies.
- Refresh token rotation with reuse detection.
- CSRF double-submit cookie plus `X-CSRF-Token` header for mutations.
- Strict CSP, `X-Content-Type-Options`, `Referrer-Policy`, frame denial.
- Rate limits on OTP request, OTP verify, order creation, and cancel.
- USA-only: Cloudflare WAF geo rule plus origin check of `CF-IPCountry`.
- Best-effort only. VPNs can bypass geo rules. No claim of perfect enforcement.
- A web app cannot keep a secret. "User API only from User Client" is enforced by same-origin cookies, CORS denial, CSRF, rate limits, WAF, and bot protection. No embedded API key.

### Admin Android

- Per-device EC P-256 keypair generated in Android Keystore.
- Pairing: one-time 128-bit code, 15-minute TTL, single use, rate limited.
- Pair endpoint is `/admin/v1/devices/pair` on the one hostname; it is the only
  mTLS-exempt admin path and only issues a client certificate.
- Server private CA signs the device CSR.
- Device certificate + API key + path-scoped Cloudflare mTLS protect admin APIs.
- API key is random 256-bit, stored hashed server-side, shown once.
- Cloudflare validates the client certificate; origin verifies the forwarded certificate fingerprint against an active, non-revoked `admin_device`.
- Cloudflare manages HTTPS certificates; no origin certificate pinning runbook exists.
- Admin login: email + password + TOTP.
- Access token 15 minutes, refresh token 14 days, rotating. Tokens stored in Keystore.
- Recovery codes hashed server-side.
- Device revocation blocks API key and certificate fingerprint.
- Single admin account, and exactly one `ACTIVE` admin device, enforced by database constraints and service checks.

### Application

- Argon2id for passwords.
- TOTP secrets encrypted at rest with an application key.
- OTP codes hashed with a server-side pepper.
- Refresh tokens, API keys, and pairing codes stored hashed.
- Signed, single-use IDs are not used as authorization.
- Every admin mutation writes an audit event.
- Input validation on every endpoint.
- Output encoding supplied by React/Compose; no HTML injection.
- Uploaded images validated by magic bytes and re-encoded server-side. EXIF stripped.
- SQL via JPA and parameterized queries only.
- Dependency and container scanning are not scripted; repeatable tooling is routed to S11.

### Privacy

- Collect only name, email, phone, order history, and notification preferences.
- No payment card data. Venmo is out of band.
- PII redaction in logs.
- Data deletion request handled manually by the admin.
- Retention: active data until deletion request. Proxmox full-container backup retention is owner-managed outside this repo.

## 12. Core flows

### Menu publication

1. Admin creates a menu and one or more bake dates.
2. Adds offerings with price, quantity limit, pickup windows, and optional customizations.
3. Publishes the menu.
4. Server schedules `MENU_PUBLISHED` notifications at the configured time, default 4 days before the first bake date at 09:00 bakery-local.
5. Published dates become visible to customers.

### Order placement

1. Customer selects a published bake date and a pickup window.
2. Menu is filtered to offerings assigned to that window.
3. Customer adds items and customizations to the cart.
4. Checkout requires a profile name and at least one verified contact channel.
5. Server validates cutoff, window capacity, per-customer limits, and offering availability.
6. Server reserves window capacity and offering quantities in one transaction.
7. Order is created as `PENDING` with `UNPAID` payment status.
8. Customer sees Venmo handle, amount, and order number.
9. Admin receives a new-order email; optional Textbee SMS goes to `ADMIN_ALERT_PHONE`. No push channel exists.

### Order acceptance and payment

1. Admin accepts a `PENDING` order before cutoff.
2. Status becomes `ACCEPTED`. Customer is notified with Venmo instructions.
3. Admin marks `PAID` when Venmo payment arrives.
4. When an order transitions to `PAID`, server sends an admin email (`ADMIN_ALERT_EMAIL`) with full bake/fulfillment order details (order number, bake date, pickup window, customer snapshots, items/options/notes, totals, payment method, paid time) as a durable digital paper trail in case of database failure.
5. If the order is accepted but never marked paid, payment is handled in cash at pickup. This is not shown as an option to customers.
6. Admin marks `READY` on baking day. Customer gets a ready notification with a maps link.
7. Admin marks `COMPLETED` after pickup.

### Expiry

- Pending orders expire at the pickup window cutoff: 12 hours before window start.
- Expired orders release window capacity and offering quantity.
- Customer is notified.
- Admin cannot accept after cutoff.

### Cancellation

- Customer may cancel `PENDING` or `ACCEPTED` orders until 00:00 bakery-local on the bake date.
- Cancellation releases capacity and quantity.
- Paid cancellations require manual Venmo refund.
- No customer cancellation once the baking day has started.
- Admin cancellation is allowed with a reason and an audit entry.
- Cancelling a menu date cancels all active orders on that date and notifies opted-in customers.

### Notifications

- Transactional email via Mailgun. `ORDER_PAID` admin email and new-order admin
  email are mandatory and go to `ADMIN_ALERT_EMAIL`.
- Transactional SMS via Textbee; optional new-order admin SMS goes to
  `ADMIN_ALERT_PHONE`. No Firebase/FCM component exists.
- Notification rows are written transactionally with an outbox event.
- Worker dispatches with retries and exponential backoff.
- Customers choose notification types per channel; an explicit all-off choice
  suppresses every customer message, including active-order menu cancellations.
  The in-app view remains available.
- SMS requires explicit phone verification and consent. STOP/START handled by
  the signed Textbee webhook (unauthenticated at the edge, geo-exempt).
- Quiet hours: no menu-announcement or reminder SMS outside 09:00–20:00 bakery-local. Transactional order-status SMS (accepted, ready, delayed, rejected, cancelled) may be sent when the event occurs.

## 13. Inventory and concurrency

- One pickup window per order.
- Window capacity counts orders, not items.
- Offering availability is per offering per bake date.
- Counters:
  - `offering.reserved_count`
  - `pickup_window.reserved_orders`
- Reservation uses conditional atomic updates:
  - `UPDATE offering SET reserved_count = reserved_count + :qty WHERE id = :id AND reserved_count + :qty <= quantity_limit`
  - `UPDATE pickup_window SET reserved_orders = reserved_orders + 1 WHERE id = :id AND reserved_orders < max_orders`
- Zero rows updated means sold out or window full. The transaction rolls back.
- Multiple lines lock offerings in ascending ID order to avoid deadlocks.
- Reducing `quantity_limit` or `max_orders` below the reserved count is allowed. Existing orders are honored. No new reservations succeed until the count drops below the new limit.
- Cancellation decrements counters. If the new limit is already met, the freed count does not open new capacity.

## 14. Data rules

- Money is integer USD cents. No floats.
- Instants are stored UTC and returned ISO-8601. Bake dates are `YYYY-MM-DD` in `America/Denver`.
- Prices, names, descriptions, photo references, ingredient text, allergens, and customization option names/deltas are snapshotted onto offerings and order lines.
- Templates can be edited without changing published menus.
- Published offerings can be edited. Existing orders keep snapshots.
- Order numbers: `BB-<yyyyMMdd>-<0001>`, sequence per bake date.
- Soft delete is not used. Status fields control visibility.
- Audit events are append-only.

## 15. Observability

- JSON logs to stdout, Docker `json-file` with rotation.
- Request ID in response header `X-Request-Id` and logs.
- Actuator: liveness, readiness, info.
- Healthchecks in Compose.
- Admin notification failure list and retry action.
- Audit log viewer API.
- No Prometheus/Grafana stack in scope. Optional host-level uptime check.
- No in-repo backup script or drill; the owner verifies Proxmox snapshot scope
  and recovery.

## 16. Failure handling

- Mailgun/Textbee outage: notification stays `FAILED`, admin can retry. Orders still work.
- Scheduler missed run: jobs are idempotent and scan for due work on next tick.
- API restart: Flyway idempotent, schedulers resume, pending notifications retry.
- Data or media loss: owner restores the whole LXC from the latest Proxmox
  container backup. Recovery-point/target depends on the owner's snapshot
  schedule; no separate pg_dump or media archive exists.
- Cloudflare outage: app unreachable. No origin fallback.
- Device CA loss or compromise: rotate the CA and re-pair (`cloudflare-mtls-ca.md`);
  there is no edge certificate pin rotation runbook.

## 17. Environments

| Environment | Purpose | Data | Access |
|---|---|---|---|
| Local | Development | Seeded fake data | `localhost`, local Compose only |
| Production | Real bakery | Real data | one hostname: `bakery.cherry-home.com` |

There is no staging environment, staging hostname, or staging compose project.
Local uses `infra/compose.dev.yml` for API + Postgres. Mailgun/Textbee are
disabled and logged to console. OTP codes appear in API logs only in the
`local` profile.

## 18. Phase map

- Server: S1 foundation, S2 auth/devices, S3 catalog/media, S4 menus/offerings/windows, S5 orders/inventory, S6 payments/notifications, S7 hardening/deploy, S8 review follow-ups.
- Customer web: W1 scaffold, W2 auth/profile, W3 menu, W4 item detail, W5 cart/checkout, W6 confirmation/status, W7 history/cancel/preferences, W8 PWA/hardening.
- Admin Android: A1 scaffold/pairing bootstrap, A2 login/device, A3 menus/dates, A4 item templates, A5 offerings/windows, A6 orders/bake list, A7 notifications, A8 push/release (FCM removed by S8).

## 19. Decision log

| ID | Decision | Reason |
|---|---|---|
| D1 | Spring Boot + PostgreSQL | Chosen by owner; transactional inventory |
| D2 | React + Vite PWA | One web app for Android/iOS |
| D3 | Kotlin Compose admin app | Native security, camera/QR, and Keystore-backed device identity |
| D4 | Cloudflare Tunnel | No inbound ports; TLS/WAF/mTLS at edge |
| D5 | Manual build/deploy/backup | Owner operates Proxmox; low scale |
| D6 | One order per pickup window | Simple capacity and pickup logistics |
| D7 | Snapshot pricing/customizations | Published menus and orders stay stable |
| D8 | Cutoff 12h before window; pending expires at cutoff | Owner decision |
| D9 | Payment state separate from fulfillment state | Venmo manual, cash at pickup possible |
| D10 | USA-only best-effort | Cloudflare geo + origin check |
| D11 | No embedded web API secret | Web apps cannot keep secrets |
| D12 | Eight server phases | Keep code reviews reviewable; S8 routes review follow-ups |
| D13 | One public hostname with path-scoped ingress and mTLS | Fewer DNS/tunnel/rule surfaces; pair path exempt from mTLS |
| D14 | No Firebase/FCM; Mailgun email and optional Textbee SMS for admin alerts | No Firebase project or service account to operate |
| D15 | Proxmox full-container backups only | Owner manages backups/builds at container level; no pg_dump/restic/staging |
