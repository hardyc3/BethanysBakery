# S9 — Metrics, Recipient Preview, and Dummy Server Test Image

- Status: implemented (final specified server phase; live ops probes owner-owned)
- Depends on: S8
- Contract: `docs/api/openapi.yaml` → Admin Notifications; `docs/architecture.md`; `infra/compose.prod.yml`
- Next: final specified server phase; A3/A6/A7/W1/A1 spec edits; older S8 deferrals stay unnumbered backlog
- Review findings: F1 metrics export, F2 admin recipient preview, F3 dummy server image for client functional tests

## Goal

Expose Prometheus metrics on a dedicated host port so a separate LXC can scrape them into Grafana; give the admin an authoritative, optional recipient preview before any customer-notification action; ship a self-contained dummy server image that web and Android functional tests can start and seed through the real API.

## Non-goals

- Production schema changes or new Flyway migrations.
- New Problem codes.
- Admin-action metrics or per-customer/per-order metric labels.
- Public/Cloudflare-routed metrics path.
- Client UI implementation in this phase; A3/A6/A7 and W1/A1 specs are edited here, their code lands in those phases.
- Registry pushes, CI/CD, or a new infrastructure service in production.
- Direct client database access to the dummy image; all seeding uses server APIs.
- Real email/SMS/push delivery from the dummy image.

## Deliverables

- `micrometer-registry-prometheus` dependency with short justification in the PR.
- Management port `9090` on `0.0.0.0` in prod/local compose; source restricted by owner firewall using `PROMETHEUS_SCRAPE_CIDR`.
- Order lifecycle counters: created, paid, cancelled, ready, delayed, rejected, expired.
- Shared `NotificationAudienceResolver` used by both enqueue and preview.
- `POST /admin/v1/notifications/recipient-preview` operation with OpenAPI schemas.
- `dummy` target in `server/Dockerfile` plus `application-dummy.yml`, entrypoint, and fixed auth constants documented in `server/README.md`.
- Updates to `docs/specs/README.md`, S8 register, and the five existing client/admin specs listed in Handoff notes.

## Data model changes

None. No new production migration. The dummy image starts a fresh embedded PostgreSQL and applies V1–V8 from S8 on every container start; no volume is mounted.

## API changes

### Metrics (not OpenAPI)

| Setting | Value |
|---|---|
| Dependency | `io.micrometer:micrometer-registry-prometheus` |
| Port | `${METRICS_PORT:9090}` |
| Bind | `${METRICS_BIND_ADDRESS:0.0.0.0}` |
| Exposure | `health,info,prometheus` |
| Path | `/actuator/prometheus` on port `9090` only |
| Auth | None at app layer; firewall allowlist only |
| Histogram | `management.metrics.distribution.percentiles-histogram.http.server.requests=true` |

- Actuator moves to the management port; existing prod healthcheck changes to `http://127.0.0.1:9090/actuator/health/readiness`.
- Prod compose publishes `0.0.0.0:9090:9090`. Local compose publishes `127.0.0.1:9090:9090`.
- `.env.example` documents `METRICS_PORT`, `METRICS_BIND_ADDRESS`, and `PROMETHEUS_SCRAPE_CIDR`; the CIDR is operator firewall config, not read by the app.
- Cloudflare gets no `/actuator/**` route. One-hostname routing from S8 stays unchanged.

### Business counters

| Metric name | Increment point |
|---|---|
| `bakery_orders_created_total` | Successful `createOrder` commit; replays do not increment. |
| `bakery_orders_paid_total` | Successful server-side paid-state transition. |
| `bakery_orders_cancelled_total` | Successful customer or admin cancellation. |
| `bakery_orders_ready_total` | Successful `markOrderReady`. |
| `bakery_orders_delayed_total` | Successful `notifyOrderDelayed`. |
| `bakery_orders_rejected_total` | Successful `rejectOrder`. |
| `bakery_orders_expired_total` | Successful expiry transition in the expiry job. |

- Tags: none. No customer IDs, order IDs, phone/email, or free text.
- Count server state transitions, not caller type. `paid` is counted when `updateOrderPayment` commits, even though that endpoint is admin-only.
- Failed validations, conflicts, idempotent replays, and no-op retries do not increment.

### Recipient preview

- Operation ID: `previewNotificationRecipients`.
- Path: `POST /admin/v1/notifications/recipient-preview?page=0&size=20`.
- Admin-only. Read-only. No outbox rows, audit events, state changes, or provider calls.
- Request fields:
  - `eventType` — one of the nine customer events from `docs/domain-model.md`.
  - `entityType` — `MENU`, `MENU_DATE`, or `ORDER`.
  - `entityId` — UUID.
- Response fields:
  - `eventType`, `asOf` (server instant).
  - `customerCount` — distinct customers who would receive at least one message now.
  - `channelCounts` — `EMAIL` and `SMS` counts, each counting distinct customers on that channel.
  - `items[]` — paged: `customerId`, `name`, `channels[]`, `emailMasked`, `phoneMasked`, `reasons[]`.
  - `page`, `size`, `total`.
- Masking reuses `NotificationApi.Mask`; full email and full phone never leave the server.
- Invalid event/entity combination → `VALIDATION_ERROR` (400). Missing entity → `NOT_FOUND` (404). `ORDER_PAID` and `ADMIN_NEW_ORDER` are rejected as `VALIDATION_ERROR`.

Audience rules — preview must match the enqueue path exactly:

| Event | Entity | Audience rule |
|---|---|---|
| `MENU_PUBLISHED` | `MENU` | Active customers; per-event preference; verified email; SMS consent + verified phone. No promotional quiet-window cutoff. |
| `ORDER_REMINDER` | `MENU_DATE` | Active customers with no `PENDING`/`ACCEPTED`/`READY`/`COMPLETED` order for that date; `isOptedIn` (email or SMS); enqueue channel checks; SMS bake-date cutoff. |
| `ORDER_ACCEPTED`, `ORDER_READY`, `ORDER_DELAYED`, `ORDER_REJECTED`, `ORDER_CANCELLED`, `ORDER_EXPIRED` | `ORDER` | The order's customer; per-event preference; verified contact; SMS consent. |
| `MENU_CANCELLED` | `MENU` or `MENU_DATE` | Customers with active orders on the affected menu/date; per-event preference including S8 all-off suppression; verified contact; SMS consent. |

## Ordered tasks

1. Add Prometheus registry dependency and management-port config; move actuator health to `9090`; permit `/actuator/prometheus` in `SecurityConfig`; update prod/local compose, healthchecks, `.env.example`, `infra/README.md`, `docs/architecture.md`, and the deploy runbook with the CIDR firewall step.
2. Add the seven order counters and increment only at committed transitions; add unit tests proving replay/conflict/no-op paths do not increment.
3. Extract `NotificationAudienceResolver` from `NotificationService` and `MenuNotificationService`; keep enqueue behavior byte-for-byte equivalent, including S8 all-off suppression and SMS quiet hours.
4. Implement `previewNotificationRecipients` on the resolver; add OpenAPI path and schemas; reuse `NotificationApi.Mask`; add pagination and stable sort by `(name, customerId)`.
5. Add integration tests for all nine customer events, including zero-recipient, all-off, SMS STOP, unverified contact, reminder already-ordered exclusion, and menu/date cancellation active-order selection.
6. Add `application-dummy.yml`, dummy seed runner, fixed OTP sender, profile inclusion in bypass checks, `Dockerfile` `dummy` target, and `docker/entrypoint-dummy.sh`.
7. Document dummy constants, build/run commands, health endpoints, and API-only seeding flow in `server/README.md`.
8. Edit `docs/specs/README.md`, S8 register, and A3/A6/A7/W1/A1 specs per Handoff notes.
9. Run `/phase-gate S9`; record results below.

## Acceptance criteria

- Given an allowed Prometheus LXC, when it scrapes `http://<host>:9090/actuator/prometheus`, then it receives `200` text/plain with JVM, HTTP, DB/Hikari, and the seven `bakery_orders_*_total` series.
- Given a disallowed source IP, when it connects to `tcp/9090`, then the host firewall drops/rejects it; no app auth prompt appears.
- Given the public hostname, when a request targets `/actuator/prometheus`, then no Cloudflare route exists and no public response is produced.
- Given a same-key order replay, when the original order is returned, then `bakery_orders_created_total` does not change.
- Given an order transitions to paid/ready/delayed/rejected/cancelled/expired, when the transaction commits, then exactly one matching counter increments.
- Given an admin preview for any customer event, when the preview is requested, then `customerCount` equals the number of distinct customers the real action would queue and `channelCounts` match the per-channel deliveries.
- Given a recipient detail row, when it is returned, then it contains name, masked email/phone, channels, and reasons; no full email, full phone, or customer free-text notes appear.
- Given all notifications off for a customer, when a `MENU_CANCELLED` preview runs, then that customer is absent and no audit/outbox row is written.
- Given a reminder preview, when a customer already has an active order for that date, then that customer is absent.
- Given a customer session, when it calls `previewNotificationRecipients`, then it receives `403` before the controller; given no session/device key, then `401`.
- Given the dummy image, when a client starts it, then API `8080` and health `9090` become ready within the documented startup window, Flyway applies V1–V8 to a fresh embedded DB, and no provider credentials are required.
- Given dummy fixed constants, when a client requests a customer OTP, then `123456` verifies; when it logs in as admin with `X-Device-Key: dummy-device-key`, then admin API calls succeed without a real pair flow.
- Given the dummy image, when tests create menus/orders through the API, then all state is local to that container and disappears when it stops.

## Test plan

- Unit: counter increment/no-increment paths; resolver parity against enqueue for each event; masking helper output.
- Integration: preview for all nine events; pagination and sort; admin-only/customer-denied; no side effects; OpenAPI operation ID present and unique.
- Config/ops: scrape from allowed and blocked CIDRs, path-route probe on Cloudflare config, compose config validation, shell syntax check on dummy entrypoint, secret scan.
- Image smoke: build `--target dummy`, start container, poll readiness, request public config, seed one admin and one customer via API, verify OTP `123456`, stop container and confirm no persisted DB volume.
- Client-spec review: A3/A6/A7/W1/A1 contain the new contract and test-harness requirements.

## Security checklist

- [x] Prometheus registry present only on management port `9090`; no public hostname route. Evidence: `application.yml`, `SecurityConfig.java`, `compose.prod.yml`, `cloudflared/config.yml.example`.
- [x] Host firewall restricted to `PROMETHEUS_SCRAPE_CIDR`; metrics endpoint has no app auth by design. Evidence: `.env.example`, `infra/runbooks/deploy.md`, `cloudflare-tunnel.md`; live firewall probe routed to owner in the S8 register.
- [x] No secret, token, OTP, email, phone, order ID, or customer ID in metric names/labels. Evidence: `OrderMetrics.java`, `MetricsIntegrationTest.java`.
- [x] Preview requires admin role and device key; customer roles denied. Evidence: `SecurityConfig.java`, `NotificationPreviewIntegrationTest.java`.
- [x] Preview response uses masked destinations only and writes no audit/outbox rows. Evidence: `NotificationApi.Mask`, `NotificationService.preview`, `NotificationPreviewIntegrationTest.java`.
- [x] Resolver matches enqueue rules, including S8 all-off suppression and SMS STOP/consent. Evidence: `NotificationAudienceResolver.java`, `MenuNotificationService.java`, `NotificationOrderEventPublisher.java`, `NotificationIntegrationTest.java`.
- [x] Dummy profile secrets are documented as non-production and cannot be used with `prod` profile. Evidence: `server/README.md`, `application-dummy.yml`, `DummyProfileGuard.java`, `DummyProfileGuardTest.java`.
- [x] Dummy image cannot reach Mailgun/Textbee/FCM; local senders only. Evidence: `DummyOtpSender`, `LocalEmailSender`, `LocalSmsSender`, profile guards, `DummyProfileIntegrationTest.java`.
- [x] Dummy image has no DB port published and no persistent volume. Evidence: `server/Dockerfile`, `application-dummy.yml`, `DummyDatabaseConfiguration.java`.
- [x] No new dependency added without PR justification. Evidence: `server/build.gradle.kts` comments for Micrometer Prometheus and embedded PostgreSQL.

## Quality gate

`/phase-gate S9` must run before commit. Agents: `spec-compliance`, `lint`, `refactor`, `test-quality`, `security`, `gap-analysis`. Blocking findings fixed or explicitly routed. Results belong in the phase PR description and in the table below.

### S9 gate results

| Agent | Status | Findings | Action |
|---|---|---|---|
| `spec-compliance` | PASS | Initial blockers: gate table/checklist unrecorded, unstable customer-event order, missing OpenAPI ID assertion, stale runbook/bind docs, swagger scope creep. | Fixed: table/checklist below, explicit ordered event list, `previewNotificationRecipients` assertion + operation-ID uniqueness, deploy/dummy-bind docs, swagger limited to `local`. Live firewall/edge/dummy-image probes owner-waived in S8 register. |
| `lint` | PASS | Initial blocker: `Map.copyOf(EnumMap)` lost customer-event order. Checkstyle, Spectral, shellcheck, hadolint, compose config, YAML parse, secret scan otherwise clean. | Fixed order; `./gradlew check` PASS. Pre-existing `openapi.yaml` yamllint brace style is baseline. |
| `refactor` | PASS | Initial blockers: unordered event list, `DummyOtpSender` logged a constant instead of its `code` parameter, dead imports, needlessly public `parseEventType`. | Fixed all four; no behavior drift. |
| `test-quality` | PASS | Initial blockers: mask expectation mismatch and dummy constants asserted only via references; no recovery-code login. | Fixed mask assertion, added literal constant checks and one-time recovery login. `./gradlew check`: 361 tests, 0 failures; JaCoCo verification PASS. |
| `security` | PASS with warnings routed | Blocker: dummy run command published admin API on all interfaces. Warnings: CIDR firewall is doc-only live, reflection in `OrderMetrics`, int overflow in preview paging, embedded Postgres dependency in single-module jar. | Fixed: loopback-only dummy run in `server/README.md` and W1 spec; overflow use `long`; reflection documented as required by exact `_created_total` exposition. Firewall/edge probes owner-waived; dependency justified in build file. No secrets/tokens/PII in labels. |
| `gap-analysis` | PASS with routed items | Blockers: gate results missing and `MENU_CANCELLED` preview/enqueue parity gap. Medium: spec index drift, wrong Dockerfile build context, no zero-recipient/bypass test. | Fixed gate table, client-spec index, Dockerfile comment, zero-recipient and active-order-bypass tests. Parity gap accepted as unnumbered backlog in S8 register because ordered task 3 forbids enqueue behavior changes in S9. |

## Definition of done

Prometheus scrape works from the allowlisted LXC and is unreachable publicly; all seven order counters behave correctly under replay and conflict; recipient preview is authoritative, masked, read-only, and admin-only for all nine customer events; the dummy image builds, starts, seeds via API, and is documented; all five existing client/admin specs plus `README.md` and S8 register are updated; `/phase-gate S9` has recorded results.

## Handoff notes

- Metrics are a separate host-port surface, not a second hostname. Do not add `/actuator/**` to Cloudflare Tunnel ingress.
- Business counters describe server state transitions, not client attribution. "Paid" is counted when the server records payment, regardless of which admin action triggered it.
- Recipient preview is point-in-time. Customers can change preferences between preview and scheduled send; the send path remains authoritative.
- Shared recipient-preview UI belongs in A7 as a reusable panel; A3 and A6 consume it. Preview copy must say the count is current and may change.
- Dummy image is built locally from `server/Dockerfile --target dummy`; no registry. Clients own their Testcontainers/Playwright wrappers. Run it with both ports bound to `127.0.0.1`; known dummy admin credentials and mTLS bypass must never be exposed to a LAN.
- Dummy authentication constants: admin `admin@dummy.local`, password `DummyAdmin!234`, TOTP secret `JBSWY3DPEHPK3PXP`, recovery code `DUMMY-12345`, device key `dummy-device-key`, customer OTP `123456`. CSRF token comes from `GET /api/v1/auth/csrf`.
- Specs edited by this phase: `docs/specs/android/A3-menus-dates.md`, `A6-orders-bake-list.md`, `A7-notifications.md`, `docs/specs/android/A1-scaffold-pairing.md`, `docs/specs/web/W1-scaffold.md`, and `docs/specs/README.md`.
- S8's former S9–S12 placeholders become unnumbered backlog so S9 is the final specified server phase.
