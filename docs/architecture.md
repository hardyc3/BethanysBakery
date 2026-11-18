# Bethany's Bakery — Architecture

Status: approved baseline  
Last updated: 2026-11-14

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
                    ┌─────────────────────────────┐
                    │        Cloudflare           │
                    │  TLS, WAF, geo, mTLS, cache │
                    └──────────┬──────────────────┘
                               │ Cloudflare Tunnel (outbound only)
          ┌────────────────────┼─────────────────────┐
          │                    │                     │
   bakery.cherry-home.com   bakery-admin-api.cherry-home.com   bakery-pair.cherry-home.com
          │                    │                     │
          ▼                    ▼                     ▼
   ┌─────────────┐      ┌─────────────────────────────────┐
   │ web (nginx) │─────▶│ api (Spring Boot)               │
   │ React SPA   │ /api │                                 │
   └─────────────┘      │  customer API + admin API       │
                        │  schedulers + notification worker│
                        └───────┬─────────────┬───────────┘
                                │             │
                         ┌──────▼─────┐  ┌────▼──────────┐
                         │ PostgreSQL │  │ media volume  │
                         └────────────┘  └───────────────┘
                                │
                         ┌──────▼─────────────────────────┐
                         │ Mailgun, Textbee, FCM          │
                         └────────────────────────────────┘
```

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
- FCM
- Android Photo Picker / CameraX
- QR scanning
- Android Keystore for device key, client certificate, API key, tokens
- Min SDK 26
- Sideloaded APK
- In-app version check
- JUnit, MockWebServer, Compose UI tests

### Infrastructure

- Alpine Linux host
- Docker Engine
- Docker Compose plugin
- Cloudflare Tunnel (`cloudflared`)
- PostgreSQL container
- nginx container for the web app
- restic for manual encrypted backups
- Private CA for admin device client certificates

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
  compose.staging.yml
  setup-alpine.sh
  .env.example
  cloudflared/
  scripts/
    build.sh
    deploy.sh
    backup.sh
    restore.sh
docs/
  initial_project_description.md
  architecture.md
  domain-model.md
  api/openapi.yaml
  specs/
    README.md
    server/S1..S7
    web/W1..W8
    android/A1..A8
```

## 7. Hostnames

Placeholders use `cherry-home.com`. Production values live in `/opt/bethanys-bakery/prod/.env`; staging uses `/opt/bethanys-bakery/staging/.env`.

| Hostname | Purpose | Protection |
|---|---|---|
| `bakery.cherry-home.com` | Customer web + public/customer API | Cloudflare WAF, US-only geo rule |
| `bakery-admin-api.cherry-home.com` | Admin API | Cloudflare mTLS client certificate + device API key |
| `bakery-pair.cherry-home.com` | One-time device pairing only | No mTLS, pairing code + rate limit |
| `staging-bakery.cherry-home.com` | Staging customer web | Cloudflare Access or basic auth |
| `staging-bakery-admin-api.cherry-home.com` | Staging admin API | mTLS |

Cloudflare Tunnel routes each hostname to the internal Docker service. No inbound ports on the Proxmox host.

## 8. Docker Compose topology

Production services:

- `web`: nginx Alpine, serves React build, proxies `/api` to `api`.
- `api`: Spring Boot, exposes `8080` only on the internal Docker network.
- `postgres`: PostgreSQL 16, internal network only, named volume.
- `cloudflared`: outbound tunnel, configured from `CLOUDFLARE_TUNNEL_TOKEN`.
- `backup`: optional manual profile container with restic and PostgreSQL client. Not always running.

Networks:

- `edge`: web and cloudflared.
- `backend`: api, web, postgres.

Volumes:

- `postgres-data`
- `media-data`
- `caddy-data` not used. Cloudflare terminates TLS.
- `backup-cache` optional.

Healthchecks:

- `api`: `GET /actuator/health/readiness`
- `postgres`: `pg_isready`
- `web`: `GET /`
- `cloudflared`: process check

Startup order: postgres healthy → api healthy → web healthy → cloudflared.

Flyway migrations run during API startup. One API replica only.

## 9. Deployment strategy

Manual and script-driven.

1. `infra/setup-alpine.sh` prepares the host:
   - installs `docker`, `docker-cli-compose`, `restic`, `openssl`, `curl`, `jq`
   - enables Docker
   - creates `/opt/bethanys-bakery/{prod,staging,secrets,backups}`
   - creates service user and permissions
   - generates the admin device CA if missing
   - writes a template `.env`
   - does not start the stack
2. Operator fills `/opt/bethanys-bakery/prod/.env` with secrets and the Cloudflare tunnel token. Local development uses a gitignored `infra/.env`.
3. `infra/scripts/build.sh` builds API and web images locally with Compose.
4. `infra/scripts/deploy.sh prod`:
   - validates env
   - runs `docker compose pull` for base images if desired
   - builds
   - starts postgres
   - runs Flyway via API startup
   - starts api, web, cloudflared
   - waits for health
5. `infra/scripts/backup.sh` performs manual backup.
6. `infra/scripts/restore.sh <backup>` restores DB and media.

Staging is a separate compose project and database on the same host, using different hostnames and volumes. Never share the production database.

Rollback: redeploy the previous image tag. Database migrations are forward-only. Destructive migrations require an explicit backup and a spec update.

## 10. Secrets and configuration

- Local secrets live in a gitignored `infra/.env` with mode `600`.
- Production secrets live in `/opt/bethanys-bakery/prod/.env`; staging in `/opt/bethanys-bakery/staging/.env`. Both mode `600`, never in git.
- `infra/.env.example` documents every variable.
- Admin CA private key lives in `/opt/bethanys-bakery/secrets/device-ca.key`, mode `600`, never in git.
- Spring Boot reads environment variables. No secrets in application YAML.
- Tokens, OTP codes, API keys, passwords, and full contact details are redacted from logs.
- Database password and restic password are generated by setup and stored only in `.env`.
- Cloudflare tunnel token is stored only in `.env`.

Configuration groups:

- `APP_*`: public base URLs, environment name, bakery name.
- `BAKERY_*`: timezone, address, maps URL, Venmo handle, support email.
- `DB_*`: PostgreSQL connection.
- `JWT_*`: signing keys, access/refresh TTLs.
- `OTP_*`: length, TTL, attempts, rate limits.
- `MAILGUN_*`, `TEXTBEE_*`, `FCM_*`.
- `ADMIN_*`: admin alert email, app version info.
- `CLOUDFLARE_*`: tunnel token, mTLS header name, country header.
- `BACKUP_*`: restic repository, password.
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
- Pair endpoint lives on the non-mTLS `pair` hostname and only issues a client certificate.
- Server private CA signs the device CSR.
- Device certificate + API key + Cloudflare mTLS protect admin APIs.
- API key is random 256-bit, stored hashed server-side, shown once.
- Cloudflare validates the client certificate; origin verifies the forwarded certificate fingerprint against an active, non-revoked `admin_device`.
- Certificate pinning in the app uses a configured pin set with backup pins. Pin rotation is an operational procedure.
- Admin login: email + password + TOTP.
- Access token 15 minutes, refresh token 14 days, rotating. Tokens stored in Keystore.
- Recovery codes hashed server-side.
- Device revocation blocks API key and certificate fingerprint.
- Single admin account enforced by configuration and a unique constraint.

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
- Dependency and container scanning are manual checklist items.

### Privacy

- Collect only name, email, phone, order history, and notification preferences.
- No payment card data. Venmo is out of band.
- PII redaction in logs.
- Data deletion request handled manually by the admin.
- Retention: active data until deletion request. Backups retained 30 days.

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
9. Admin receives a push/email notification.

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

- Transactional email via Mailgun.
- Transactional SMS via Textbee.
- Admin push via FCM.
- Admin email fallback.
- Notification rows are written transactionally with an outbox event.
- Worker dispatches with retries and exponential backoff.
- SMS requires explicit consent. STOP/START handled by Textbee webhook.
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
- Backup script exits non-zero on failure. Operator runs a monthly restore drill.

## 16. Failure handling

- Mailgun/Textbee/FCM outage: notification stays `FAILED`, admin can retry. Orders still work.
- Scheduler missed run: jobs are idempotent and scan for due work on next tick.
- API restart: Flyway idempotent, schedulers resume, pending notifications retry.
- Database loss: restore from latest nightly backup. Up to 24 hours of orders can be lost. Operator may increase backup frequency.
- Media loss: restore media archive from backup.
- Cloudflare outage: app unreachable. No origin fallback.
- Certificate pin rotation: app build or remote-config pin update. Documented operational procedure.

## 17. Environments

| Environment | Purpose | Data | Hostnames |
|---|---|---|---|
| Local | Development | Seeded fake data | localhost |
| Staging | Pre-release checks | Fake data | staging-* |
| Production | Real bakery | Real data | bakery.cherry-home.com |

Local uses Docker Compose for API + Postgres. Mailgun/Textbee/FCM are disabled and logged to console. OTP codes appear in API logs only in the `local` profile.

## 18. Phase map

- Server: S1 foundation, S2 auth/devices, S3 catalog/media, S4 menus/offerings/windows, S5 orders/inventory, S6 payments/notifications, S7 hardening/deploy.
- Customer web: W1 scaffold, W2 auth/profile, W3 menu, W4 item detail, W5 cart/checkout, W6 confirmation/status, W7 history/cancel/preferences, W8 PWA/hardening.
- Admin Android: A1 scaffold/pairing bootstrap, A2 login/device, A3 menus/dates, A4 item templates, A5 offerings/windows, A6 orders/bake list, A7 notifications, A8 push/release.

## 19. Decision log

| ID | Decision | Reason |
|---|---|---|
| D1 | Spring Boot + PostgreSQL | Chosen by owner; transactional inventory |
| D2 | React + Vite PWA | One web app for Android/iOS |
| D3 | Kotlin Compose admin app | Native security and camera/QR/FCM support |
| D4 | Cloudflare Tunnel | No inbound ports; TLS/WAF/mTLS at edge |
| D5 | Manual build/deploy/backup | Owner operates Proxmox; low scale |
| D6 | One order per pickup window | Simple capacity and pickup logistics |
| D7 | Snapshot pricing/customizations | Published menus and orders stay stable |
| D8 | Cutoff 12h before window; pending expires at cutoff | Owner decision |
| D9 | Payment state separate from fulfillment state | Venmo manual, cash at pickup possible |
| D10 | USA-only best-effort | Cloudflare geo + origin check |
| D11 | No embedded web API secret | Web apps cannot keep secrets |
| D12 | Seven server phases | Keep code reviews reviewable |
