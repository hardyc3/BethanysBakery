# S8 — Review Follow-ups, Submission Tracking, and Ops Simplification

- Status: ready
- Depends on: S7
- Contract: `docs/api/openapi.yaml` → Public config, Customer orders, Notification preferences, Admin devices, Admin notifications
- Next: follow-up phases S9–S12 (routed here, not yet specified); W5/W7/W8/A7/A8 coordination
- Owner decisions: recorded 2026-11-19 in the phase review

## Goal

Close the owner review findings without changing the order reservation model: prove customer/admin isolation, record client submission timestamps for duplicate-order diagnosis while keeping the existing idempotency replay behavior, give customers explicit notification choices and an exact opt-out disclosure, simplify operations to one Cloudflare-managed hostname on one Proxmox LXC, and route every deferred S1–S7 finding to a named follow-up phase.

## Owner decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Add one new server phase `S8`; do not rewrite S1–S7. | Keeps implemented phases reviewable; deferrals stay visible. |
| D2 | Keep Cloudflare mTLS + device pairing + API key + TOTP. Enforce exactly one active admin device. | Owner wants control over the single admin app; removing mTLS would weaken that control. |
| D3 | Collapse three hostnames to one. mTLS becomes a path rule on `/admin/v1/**`, with `/admin/v1/devices/pair` exempt. | Cloudflare manages HTTPS certificates; fewer DNS/tunnel/rule surfaces. |
| D4 | Keep Mailgun for email and Textbee for SMS. Replace FCM admin push with Mailgun email and optional Textbee SMS. Record an AWS SES + SNS review as a follow-up. | Current volume is ~50 orders/week and ~20 SMS/baking day; no AWS account or US A2P 10DLC registration needed now. SNS US SMS requires company/campaign registration, recurring fees, and per-message charges. |
| D5 | Remove Firebase/FCM entirely. | FCM was used only for admin new-order push; `ORDER_PAID` email and new-order email fallback already exist. No Firebase project/service-account JSON to operate. |
| D6 | Keep current idempotency semantics: same key always returns the original order. Add a client timestamp and payload fingerprint for diagnosis only. | No second order, no stock changes, no replacement flow. Client clocks are untrusted and cannot decide which order is authoritative. |
| D7 | Proxmox full-container backups only. Delete pg_dump/restic/restore automation and staging. Build with `git pull` + `docker compose up -d --build` inside the LXC. | Owner manages backups and builds at the container level; no CI/CD, no registry, no separate restore stack. |
| D8 | Customer selects notification types per channel; selecting none sends nothing and shows exact copy of what is missed. In-app status remains available. | Explicit consent; no silent announcements or order-status messages after full opt-out. |
| D9 | Keep US-only geo rule and origin `CF-IPCountry` check. | Original requirement unchanged. |
| D10 | All S1–S7 deferred findings are routed to new phases (register below), not implemented here. | Keeps S8 reviewable; each follow-up gets an owner. |

## Phase completion audit

No phase header has been flipped from `Status: ready` (S1:3 through S7:3). Evidence from gates and git:

| Phase | Delivered | Not complete / carried |
|---|---|---|
| S1 | Foundation, local compose, OpenAPI, errors (commit `11354ac`). | No gate results; all 8 checklist boxes unchecked (`S1-foundation.md:153-160`); local DB defaults and Gradle wrapper fallback remain. Routed to S9/S11. |
| S2 | Auth, OTP, sessions, device pairing, audit; 6 gates PASS (`S2-auth-devices.md:266-275`). | 11 checklist boxes unchecked; in-memory per-IP limiter; dev-compose secret/bypass handoff. Routed to S9. This phase adds the one-active-device invariant. |
| S3 | Catalog/media; gate findings table (`S3-catalog-media.md:151-168`). | Orphan files, cache purge, original filename, dependency pinning, proxy upload cap. Routed to S10/S11/W8. |
| S4 | Menus/offerings; 6 gates PASS (`S4-menus-offerings.md:357-366`). | True media-reference race test, regenerated snapshot IDs, sparse `photoVariants`, test backlog. Routed to S10/client phases. |
| S5 | Orders/inventory; 6 gates PASS (`S5-orders-inventory.md:440-449`). | Audit action naming, 5 test gaps, operation-ID assertion. Routed to S9. This phase proves isolation and adds submission tracking. |
| S6 | Payments/notifications; 9 gate rows PASS (`S6-notifications-payments.md:331-344`). | Non-blocking code debt, test-profile provider skip, hardcoded brand. Routed to S9/S12. This phase removes FCM. |
| S7 | 73 files, 19 runbooks, prod/staging compose, scripts, validator, tooling (`d46f85d`). | No `/phase-gate S7` results; all 14 checklist boxes unchecked (`S7-hardening-deploy.md:337-350`); no executed probes/scans/restore drill; W8 web image missing. Routed to S9/S10/S11/W8. |

Deliverable: flip S1–S6 headers to `Status: implemented (follow-ups routed)` and S7 to `Status: implemented, verification pending`, each with a link to the register below. Do not alter their acceptance criteria.

## Non-goals

- Changing reservation, cutoff, cancellation, or payment rules.
- Replacing an order with a "newer" one or auto-refunding/reserving stock on payload mismatch.
- Adding AWS services or an A2P 10DLC campaign in this phase.
- Removing mTLS, pairing, password, or TOTP.
- Implementing client UI (W5/W7/A7/A8/W8) or a web image.
- CI/CD, Kubernetes, registry pushes, or offsite backup copies.
- E2E production verification that needs the W8 web image; that remains a W8→S9 dependency.

## Data model — `V8__review-followups.sql`

Add only:

- `orders.client_submitted_at timestamptz NULL` — optional web-generated submission time, stored verbatim, never used for business ordering.
- `orders.request_fingerprint varchar(64) NULL` — lowercase SHA-256 hex of the canonical request (see below).
- `CREATE UNIQUE INDEX uq_admin_device_single_active ON admin_device ((status)) WHERE status = 'ACTIVE';` — enforces one active admin device. Migration must fail with an explicit message if more than one device is `ACTIVE`; operator revokes extras before deploy.

Do not drop `admin_device.fcm_token` / `fcm_updated_at` here; dead columns are harmless and their removal is routed to S10. Do not edit V1–V7.

Canonical fingerprint input: menu date ID, pickup window ID, trimmed order notes, and each item's offering ID, quantity, sorted option ID set, and trimmed line notes, with items sorted by `(offeringId, sorted option IDs, notes)`. Recompute after duplicate-line normalization. The fingerprint is an observability signal only.

## API changes

- `createOrder`: add optional `client_submitted_at` (ISO-8601 `Instant`, `@PastOrPresent` not enforced; server accepts both past and skewed future values and logs raw + server-received time). Request field name `client_submitted_at`, alias `clientSubmittedAt`. Response shape unchanged. Still `201` on first create, `200` on any replay with the same key. Still no price/total fields from the client.
- Replay behavior:
  - Same key + same fingerprint → return stored order, no new rows, counters, outbox rows, or business audit events. Write one `ORDER_IDEMPOTENCY_REPLAY` audit row with `payloadChanged=false`, incoming `clientSubmittedAt`, and server-received time.
  - Same key + different fingerprint → return stored order unchanged, same status code `200`, no stock changes. Write one `ORDER_IDEMPOTENCY_REPLAY` audit row with `payloadChanged=true` and both timestamps. No new Problem code, no conflict response.
  - Concurrency: keep the per-customer row lock and partial unique index; a unique-index violation on the fallback path must be handled as a replay, not a 500.
- `getPublicConfig`: extend `PublicConfig` with `notificationOptions`:
  - `events[]`: `eventType` plus `emailAvailable` (always true when email configured) and `smsAvailable` (true only when SMS provider configured). Use the nine existing event types from `docs/domain-model.md:394-404`.
  - `noneSelectedMessage`: exact server-owned copy, used verbatim by the client when every channel is off:

    > If you turn off all notifications, you will not receive any email or text message about: new weekly menus, reminders before baking day, order accepted, order ready, order delayed, order rejected, order cancelled, order expired, or a cancelled menu/date. You can still open the app any time to check your orders, payment status, and pickup details. Text messages also require phone verification and are stopped if you reply STOP.

- `getNotificationPreferences` / `updateNotificationPreferences`: no schema change. An explicit all-off `PUT` must persist `false` for every event/channel and suppress **all** customer notifications, including the active-order `MENU_CANCELLED` notices that currently bypass preferences (`S6-notifications-payments.md:111`). `ORDER_PAID` is an admin message and is unaffected.
- Remove `updateAdminPushToken` operation, `PushTokenRequest` schema, and `fcmRegistered` from `AdminDevice`. Remove `FCM_PROJECT_ID` / `FCM_SERVICE_ACCOUNT_JSON` from `.env.example`, `application.yml`, config classes, and compose files.
- Admin new-order message: email to `ADMIN_ALERT_EMAIL` (key `ADMIN_NEW_ORDER_EMAIL:<orderId>`) always; optional SMS to `ADMIN_ALERT_PHONE` (new env var, key `ADMIN_NEW_ORDER_SMS:<orderId>`) when set. Remove the `ADMIN_NEW_ORDER:<orderId>` push key. No push token registration, no `NO_ACTIVE_FCM_TOKEN` state, no push fallback logic. Failed email/SMS follow the existing outbox retry/backoff and admin retry endpoint. `ORDER_PAID` email stays mandatory and unchanged.
- Hostnames: one public hostname. Cloudflare Tunnel ingress rules: `/admin/v1` → `api:8080`, `/api` → `api:8080`, `/` → `web:80`. Cloudflare mTLS rule: block `/admin/v1/**` when client cert is unverified, except `/admin/v1/devices/pair`. `/api/v1/webhooks/textbee` stays unauthenticated at the edge and signature-verified at origin. Geo rule remains on `/api/v1/**` and webhooks stay exempt; `EdgeRequestFilter` stays path-based, not host-based.
- Problem codes: no new codes. Use existing `INVALID_STATE` (409) when a second device tries to pair while one is active.

## Ordered tasks

1. Add `V8__review-followups.sql`; verify it fails loudly with two active devices and applies cleanly on an S7 database.
2. Add `client_submitted_at` to `CreateOrderRequest`, persist it, compute/store the canonical fingerprint, and add replay/fingerprint handling plus the unique-violation fallback in `OrderService`.
3. Add `ORDER_IDEMPOTENCY_REPLAY` audit rows and tests; keep 201/200 behavior unchanged.
4. Enforce one active admin device in migration and in `DevicePairingService`/`AdminDeviceService`; return 409 `INVALID_STATE` on second pairing.
5. Prove isolation: add cross-customer and non-admin regression tests for every customer order/preference endpoint; verify admin-only access to `/admin/v1/**` and mTLS/pairing exemptions.
6. Extend `PublicConfig` with notification options and exact disclosure copy; update OpenAPI and `PublicConfigController`.
7. Change preference suppression so explicit all-off suppresses active-order menu-cancellation notices; add tests.
8. Remove FCM sender/config/token endpoint/schema/runbook; add Mailgun admin email + optional Textbee admin SMS and idempotency keys; update `.env.example`, compose, `application.yml`, and A7/A8 spec handoff notes.
9. Collapse hostnames: one tunnel route set, path-scoped mTLS rule, updated `cloudflare-tunnel.md`, `cloudflare-mtls-ca.md`, and `config.yml.example`. Keep geo checks path-based; do not hardcode hostnames in application code.
10. Simplify ops: remove `compose.staging.yml`, staging env/hostname sections, `backup.sh`, `restore.sh`, `build.sh`, `deploy.sh`, `check-env.sh`, restic backup profile and `BACKUP_*` vars, `backup-restore.md`, `database-restore.md`, `cloudflare-cache.md`, `certificate-pinning-rotation.md`, `security-scanning.md`, and `encryption-key-rotation.md`. Keep `generate-ca.sh`, `pair-device.sh`, `bootstrap-admin.sh`, `status.sh`, `logs.sh` if still useful; document raw `git pull` + `docker compose up -d --build` commands in `deploy.md`.
11. Rewrite `infra/README.md` and `docs/architecture.md` host/topology/deploy/backup sections to one hostname, one prod environment, one LXC, Proxmox backups. Keep local dev compose.
12. Add the phase completion audit status flips and the follow-up register links to S1–S7 and `docs/specs/README.md`.
13. Run `/phase-gate S8`; record results here.

## Acceptance criteria

- Given customer A and customer B orders, when A reads, lists, or cancels B's order, then 404 `NOT_FOUND` for every endpoint and no counters change.
- Given an authenticated customer, when they call any `/admin/v1/**` endpoint, then 403 before reaching the controller; given an unpaired or revoked device, then 401.
- Given one active admin device, when a second device pairs, then 409 `INVALID_STATE` and the first device keeps working; after revoking the first, pairing succeeds.
- Given the same `idempotency_key` and identical payload, when the request repeats, then the original order returns with `200`, no second order, no counter/outbox change, and one replay audit row with `payloadChanged=false`.
- Given the same key and a different item set, when the request repeats, then the original order still returns with `200`, no stock changes, and one replay audit row containing both client and server timestamps and `payloadChanged=true`.
- Given a client-submitted timestamp in the future or far in the past, when the order is created, then it is stored/logged but never changes validation, cutoff, expiry, or order-number behavior.
- Given concurrent same-key submissions, when both commit, then exactly one order exists and the loser receives `200` with that order, not `500`.
- Given all notification preferences off, when a menu/date cancellation affects that customer, then no customer notification row is queued for any channel; `ORDER_PAID` admin email is still queued.
- Given `GET /api/v1/config`, when the client renders the no-notifications state, then `noneSelectedMessage` lists all nine event types and the SMS consent/STOP caveat verbatim.
- Given the new hostname setup, when an admin request without a client certificate hits `/admin/v1/orders`, then Cloudflare blocks it; when the same request hits `/admin/v1/devices/pair`, then it reaches the origin and is rate limited; when a non-US customer request hits `/api/v1/menu-dates`, then 403 `COUNTRY_BLOCKED`.
- Given no FCM configuration, when a new order is created, then an admin email is queued; with `ADMIN_ALERT_PHONE` set, an SMS is queued with its own idempotency key; no push sender, token endpoint, or FCM env vars remain.
- Given the LXC workflow, when the operator pulls the repo and runs `docker compose up -d --build`, then api/postgres/web/cloudflared become healthy; no restic, staging, pg_dump, or restore scripts/runbooks remain.
- Given S1–S7, when this phase completes, then their headers carry implemented status, all deferred notes appear in the register below, and S8's quality gate has recorded results.

## Test plan

- Unit: canonical fingerprint stability across item/option ordering and duplicate-line merge; replay audit payload.
- Integration: cross-customer GET/list/cancel for every customer order endpoint; admin-only access to every `/admin/v1/**` group; mTLS-pairing exemption path.
- Integration: same-key replay with identical and differing payloads; unique-index-violation fallback; `client_submitted_at` persistence and skew tolerance.
- Integration: all-off preferences across all nine events including active-order `MENU_CANCELLED`; public config copy equals the server constant.
- Integration: admin new-order email and optional SMS with fixed providers; no FCM code path; `ORDER_PAID` email unchanged.
- Concurrency: two simultaneous same-key creates; two simultaneous pairing attempts with one active device.
- Config/ops: OpenAPI validation (operation IDs present/unique), one-hostname compose config, one-active-device migration upgrade from S7, shell syntax check on remaining scripts, secret scan.
- Manual: Cloudflare path mTLS probe, geo probe, external origin probe, pairing probe; no automated CI/CD.

## Security checklist

- [x] One active admin device enforced at DB and service level; revocation immediate. Evidence: `V8__review-followups.sql`, `DevicePairingService`, `AdminDeviceService`, `DevicePairingIntegrationTest`.
- [x] mTLS required on `/admin/v1/**` except `/admin/v1/devices/pair`; origin still checks cert fingerprint. Evidence: path-scoped Cloudflare rules + matrix guard in `infra/runbooks/cloudflare-mtls-ca.md` and `cloudflare-tunnel.md`; origin check in `RequestAuthenticationFilter`/`AdminDeviceService`. Live probe waived below.
- [x] Customer isolation proven for every order/preference endpoint; admin endpoints deny customers. Evidence: `OrderIntegrationTest`, `OrderReplayIntegrationTest`, `NotificationIntegrationTest`, `AdminAccessIntegrationTest` (customer cookie denied on all admin paths, incl. matrix variants).
- [x] No client timestamp or fingerprint can change order state, totals, or stock. Evidence: `RequestFingerprint`, `OrderService`, `RequestFingerprintTest`, `OrderReplayIntegrationTest`; fields are diagnostic-only.
- [x] Replay audit contains no secrets, full emails, full phone numbers, or free-text reasons. Evidence: `OrderService.replayAuditPayload`, `OrderReplayAuditPayloadTest`.
- [x] Full opt-out suppresses all customer messages, including previously exempt notices. Evidence: `NotificationService`, `NotificationIntegrationTest`; `ORDER_PAID` admin email retained.
- [x] Admin phone/email destinations masked in API and logs. Evidence: `NotificationApi.Mask`, `NotificationServiceAdminAlertTest`, sender tests.
- [x] No FCM credentials, tokens, or service-account JSON in repo/env/runtime. Evidence: code/config/OpenAPI scans clean; dead DB columns routed to S10.
- [x] Cloudflare handles HTTPS certificates; no origin cert or ACME artifacts. Evidence: one-hostname tunnel/deploy docs; no origin cert config retained.
- [x] Secrets still mode `600` and gitignored; US geo rule and origin check verified. Evidence: `infra/setup-alpine.sh`, `.gitignore`, `EdgeSecurityIntegrationTest`; live geo/origin probe waived below.
- [x] Proxmox backup scope verified to include Docker volumes; secrets recoverable by owner. Evidence: owner-scoped waiver below; in-repo backup/restore tooling removed.

## Quality gate

`/phase-gate S8` must run before commit. Agents: `spec-compliance`, `lint`, `refactor`, `test-quality`, `security`, `gap-analysis`. Blocking findings fixed or explicitly routed in the register below. Results belong in the phase PR description and in the table below.

### S8 gate results

| Agent | Status | Findings | Action |
|---|---|---|---|
| `spec-compliance` | PASS | Reran S8 gate on `cd41fb9` + uncommitted fixes. AC103–AC115, D1–D10, test plan, signed security checklist, DoD, follow-up register, explicit manual waivers, and all prior blocker resolutions verified. Latest suite: 56 suites / 337 tests / 0 failures / 0 errors / 0 skipped; Spectral clean; shell harness PASS=8 SKIP=1. Live Cloudflare/LXC/Proxmox probes remain routed Ops-only waivers. | No blocking findings; process register item closed. |
| `lint` | PASS | — | — |
| `refactor` | PASS | — | — |
| `test-quality` | PASS | 337 tests green, including new matrix-path and customer-on-admin regression tests. | — |
| `security` | PASS (warnings routed) | Encoded `%3b` / dot-slash / double-slash path variants (origin 404, no leak); local-profile plaintext OTP log; dead `application-staging.yml`; live Cloudflare/LXC/Proxmox probes waived. | First three added to the follow-up register below; probes already routed to the Ops-only waiver rows. |
| `gap-analysis` | PASS | Future-spec FCM drift (A8 body + A1/A2/A7 references); unrecorded gate results; three unregistered security warnings. | Rewrote FCM-void sections in `A8-push-release.md`, clarified A1/A2 non-goals/handoff; added register rows and filled this table. No blocking gaps remain. |

## Follow-up register (all routed, none implemented here)

Proposed destination phases are placeholders; create their specs when work starts.

| Route | Carried item | Source |
|---|---|---|
| S9 verification & closeout | Run `/phase-gate S7`; sign the 14 unchecked security-checklist items; record probes, scans, restore drill, E2E flow | `S7-hardening-deploy.md:337-350,239-268,310-322` |
| S9 | Replace in-memory `RateLimiter` or document single-instance assumption; reconcile edge WAF rules | `S7:76-81`, `RateLimiter.java:11-14` |
| S9 | Decide `ORDER_CANCELLED_BY_SYSTEM` vs reused `ORDER_CANCELLED_BY_ADMIN`; update consumers | `S7:99`, `S5:464` |
| S9 | Close remaining S5 test gaps: fixed `Clock` expiry, whole-menu cancellation, concurrent same-key replay, blank name/unverified contact/merged qty >99/notes caps | `S5:468`, `S7:254-262` |
| S9 | Access token remains valid after logout/contact change until JWT expiry; decide revalidation policy | `RequestAuthenticationFilter.java:113-131`, `SessionService.java:82-95` |
| S9 | Local DB defaults, Gradle wrapper fallback, JSON log encoder, access logging | `S1-foundation.md:190-193` |
| S9 | Test-profile provider-config skip; hardcoded bakery name in message copy; `boolean[]` preference tuple | `S6:23-26,339` |
| S10 media & data hygiene | Media cache purge policy/probe; add reconciliation job (runbook only today); true `lockAndCheckActive` race test | `S7:106-108`, `S3:154,158`, `S4:374` |
| S10 | Original-media-filename retention decision/forward migration; drop dead FCM columns | `S7:110`, `Media.java:26-27` |
| S10 | Dependency digest pinning and image/dependency scan artifacts | `S7:111`, `build.gradle.kts:45-48` |
| S11 developer tooling | Repeatable yamllint/hadolint/Spectral/OpenAPI/gitleaks check; operation-ID assertion; coverage threshold; optional Spotless/PMD/ErrorProne | `S7:328-333`, `S5:466`, `S6:23` |
| S12 provider review | Evaluate AWS SES (email) + SNS/End User Messaging (SMS) vs Mailgun+Textbee; include US A2P 10DLC registration cost and Textbee device-reliability risk | Owner decision D4 |
| W5 client | Send `client_submitted_at` on order create; generate a new key after user edits | This spec, API changes |
| W7 client | Consent chooser, all-off disclosure using server copy, per-event labels | This spec, `W7-history-cancel-prefs.md:116` |
| W8 client | Web image for full-stack HTTPS verification; 10 MB proxy cap and security headers | `W8-pwa-hardening.md:195`, `S3:135,164` |
| A7/A8 client | Remove FCM token registration/push payload handling and edge cert pinning rotation; keep mTLS pairing | This spec, `A8-push-release.md:195-196` |
| Ops-only | Store bootstrap password/recovery codes off-repo; upload device CA to Cloudflare; verify Proxmox snapshot contains Docker volumes | `S2:290`, `S6:352`, `S7:338` |
| Ops-only (S8 manual waiver) | Execute and record Cloudflare path-mTLS block/verified-allow, `/admin/v1/devices/pair` exemption + rate-limit, non-US geo 403, external-origin block, and pairing probes. Agent environment cannot reach Cloudflare or the LXC; static config/tests pass, live probes routed to owner before prod cutover. | This spec: AC10, test plan "Manual"; `infra/runbooks/cloudflare-tunnel.md`, `infra/runbooks/cloudflare-mtls-ca.md` |
| Ops-only (S8 LXC smoke waiver) | On owner LXC, run `git pull` + `docker compose up -d --build`; record api/postgres/web/cloudflared healthy and Proxmox snapshot volume scope. Agent environment verified compose syntax and remaining shell scripts only. | This spec: AC12, DoD; `infra/runbooks/deploy.md` |
| S9 edge hardening | Decode-safe path checks for encoded `%3b`, dot-slash and double-slash variants. Origin fails closed today (404/no leak) for `/admin/v1;x/**` via `RequestAuthenticationFilter`, but the raw `;` guard and Cloudflare `contains ";"`, `/api/v1/` WAF prefixes do not match encoded forms. Align edge and origin rules and add a probe; do not treat as an auth bypass in S8. | S8 security gate warning; `RequestAuthenticationFilter.java:59-65`, `EdgeRequestFilter.java:36-37`, `infra/runbooks/cloudflare-mtls-ca.md:28-36` |
| S9 | Remove dead `application-staging.yml` and residual `staging` profile branches (`@Profile({"staging","prod"})`, `ProductionConfigValidator`, `ApiExceptionHandler`) once owner confirms no staging activation is needed; S8 removed staging compose/deploy but deliberately left profile code intact. | S8 security gate warning; `server/src/main/resources/application-staging.yml`, `ProviderOtpSender.java:18`, `ProductionConfigValidator.java:15` |
| S9 | Local profile logs OTP plaintext by design (`docs/specs/README.md` local behavior) — confirm `LocalOtpSender` cannot activate under `prod` and add the guard to the S7 checklist sign-off. | S8 security gate warning; `S6-notifications-payments.md:27`, `S7-hardening-deploy.md:60`, `docs/specs/README.md:195-201` |
| Process (closed) | Initial `/phase-gate S8` `spec-compliance` blockers fixed; formal rerun completed with PASS. No process item remains. | S8 gate results table above |
| Process (accepted) | Commit `cd41fb9` uses a non-Conventional subject (`phase8`). Owner chose to keep local hashes unchanged; `5ae0c5b` and later commits follow Conventional Commits. | `docs/specs/README.md` commit-style rule; code-review Standards axis |

## Definition of done

Migration applies on S7 and empty databases, all acceptance criteria pass, S1–S7 statuses and the follow-up register are updated, one-hostname/path-mTLS config is documented and probed, FCM is absent, admin email/SMS and all-off suppression have tests, minimal LXC runbooks are the only ops docs, and `/phase-gate S8` has recorded results.

## Handoff notes

- Firebase is Google's app platform; FCM (Firebase Cloud Messaging) was the free push channel used only to tell the admin about new orders. It required a Firebase project, service-account JSON, and Android token registration. Those are gone in S8; Mailgun email and optional Textbee SMS cover admin alerts.
- Textbee sends SMS through a paired Android device, so delivery depends on that device being powered and online. AWS SNS is more robust but US SMS needs A2P 10DLC registration (one-time vetting fees, low-volume campaign ~$2/month, number lease, plus per-message fees). S12 owns that review; no change now.
- `client_submitted_at` is diagnostic. It is stored and audited, but the server's `createdAt` and idempotency key remain authoritative. A user who wants to change an order cancels and re-orders.
- One hostname means Cloudflare rules are path-scoped; do not reintroduce the pair or admin-api hostnames without a spec update.
- Proxmox container backups include the Docker volumes only if they live inside the LXC; verify once and keep the device CA key and `.env` recoverable outside the snapshot.
- With one hostname, the tunnel routes `/api` and `/admin/v1` straight to `api:8080`; W8's nginx `/api` proxy stays for local development and is not used for production ingress.
- Full-stack HTTPS acceptance remains blocked on W8's web image; S9 owns that verification after W8 lands.
