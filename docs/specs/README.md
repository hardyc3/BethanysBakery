# Spec-Driven Development Workflow

Status: active  
Last updated: 2026-11-18

## 1. Purpose

Each phase is a small, reviewable unit implemented by one LLM agent or one developer. Phase specs are the source of truth for scope, contract, acceptance criteria, and definition of done.

Shared sources of truth:

- `docs/architecture.md` — system design, deploy, security.
- `docs/domain-model.md` — entities, states, invariants.
- `docs/api/openapi.yaml` — HTTP contract.
- `docs/specs/**` — phase scope and tasks.

If a phase requires a contract change, update the spec and OpenAPI in the same pull request. Do not silently drift.

## 2. Phase order

### Server

```text
S1 → S2 → S3 → S4 → S5 → S6 → S7 → S8 → S9
```

S7 can validate the API/Postgres/Cloudflare stack after S6. Full-stack HTTPS verification of the customer web container waits for W8, which owns `web/Dockerfile` and `web/nginx.conf`. S8 records the owner review decisions, the phase-completion audit, and the follow-up register. S9 is the final specified server phase: metrics export, recipient preview, and the dummy server test image. Older deferred items in S8 stay unnumbered backlog.

Deferred findings are tracked in the [S8 follow-up register](server/S8-review-followups.md#follow-up-register-all-routed-none-implemented-here). S1–S6 are marked `implemented (follow-ups routed)`, S7 is `implemented, verification pending`, and every phase header links to that register. S8 gate results live in [the S8 gate table](server/S8-review-followups.md#s8-gate-results).

### Customer web

```text
W1 → W2 → W3 → W4 → W5 → W6 → W7 → W8
```

Web dependencies:

- W1 needs S1.
- W2 needs S2.
- W3/W4 need S3 + S4.
- W5 needs S5.
- W6 needs S5 + S6.
- W7 needs S5 + S6.
- W8 needs all prior phases.
- W5 and W7 consume S8's `client_submitted_at` and notification disclosure contract.
- W1 consumes S9's dummy server image contract for functional tests.

### Admin Android

```text
A1 → A2 → A3 → A4 → A5 → A6 → A7 → A8
```

Android dependencies:

- A1 needs S1 + S2 pairing contract.
- A2 needs S2.
- A3 needs S4.
- A4 needs S3.
- A5 needs S4.
- A6 needs S5 + S6.
- A7 needs S6.
- A8 needs S6 + S7; A7/A8 must absorb S8's FCM removal, single-active-device rule, and one-hostname routing.
- A1 consumes S9's dummy server image contract for functional tests.
- A3, A6, and A7 consume S9's recipient-preview contract.

A phase may start only when its dependencies are merged and their tests pass.

### Problem codes

Use these stable `Problem.code` values. Do not invent a new code without updating `docs/api/openapi.yaml` and this table.

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Field or body validation failed; see `errors[]`. |
| `NOT_FOUND` | 404 | Resource does not exist or is not visible to the caller. |
| `METHOD_NOT_ALLOWED` | 405 | Unsupported HTTP method. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 or 400 | Unsupported body/declared content type (415); image magic-byte mismatch (400, per S3). |
| `INTERNAL_ERROR` | 500 | Unexpected server failure; no internals leaked. |
| `RATE_LIMITED` | 429 | Too many requests; honor `Retry-After`. |
| `PAYLOAD_TOO_LARGE` | 413 | Upload exceeds the configured media size limit. |
| `CSRF_INVALID` | 403 | Missing or mismatched CSRF token on a customer mutation. |
| `OTP_INVALID` | 400 | Wrong OTP code. |
| `OTP_EXPIRED` | 400 | OTP expired, used, or locked. |
| `SMS_CONSENT_REQUIRED` | 400 | SMS requested without explicit consent. |
| `SOLD_OUT` | 409 | Offering has insufficient remaining quantity. |
| `WINDOW_FULL` | 409 | Pickup window capacity is exhausted. |
| `CUTOFF_PASSED` | 409 | Ordering or acceptance cutoff has passed. |
| `CANCELLATION_CLOSED` | 409 | Customer cancellation window has closed. |
| `CUSTOMER_LIMIT_EXCEEDED` | 409 | Per-customer limit for that offering/date exceeded. |
| `OFFERING_NOT_AVAILABLE` | 409 | Offering is cancelled, off-menu, or not assigned to the window. |
| `MENU_NOT_AVAILABLE` | 409 | Menu date or parent menu is not published (S5 order creation). |
| `WINDOW_NOT_AVAILABLE` | 409 | Pickup window is inactive or does not belong to the requested date (S5 order creation). |
| `INVALID_CUSTOMIZATION` | 400 | Unknown, duplicate, unavailable, or invalid option selection. |
| `PUBLISH_VALIDATION_FAILED` | 409 | Menu is missing dates, windows, offerings, or assignments. |
| `INVALID_STATE` | 409 | Resource is in a state that forbids the requested action (e.g. editing a cancelled menu or deleting a published menu). |
| `WINDOW_IN_USE` | 409 | Pickup window cannot be deleted because it is referenced. |
| `MEDIA_IN_USE` | 409 | Media cannot be deleted because a template or offering references it. |
| `COUNTRY_BLOCKED` | 403 | Request did not originate from an allowed country. |
| `NOT_CONFIGURED` | 503 | A provider or feature is not enabled in this environment. |
| `INVALID_SIGNATURE` | 401 | Webhook HMAC signature mismatch. |
| `ORDER_NOT_PAYABLE` | 409 | Order status forbids a payment update. |
| `ORDER_NOT_ACTIVE` | 409 | Order is not active for the requested admin action. |
| `NOT_RETRYABLE` | 409 | Notification is not in a retryable state. |
| `OTP_DELIVERY_FAILED` | 503 | OTP provider rejected the message. |

## 3. Required spec sections

Every phase spec contains the applicable sections below. Client phases omit data-model/migration sections when there is no schema change and use `API integration` for operation IDs. Server phases name every migration explicitly.

1. Header: ID, title, dependencies, contract references.
2. Goal.
3. Non-goals.
4. Deliverables.
5. Data model changes and exact Flyway migration names (server phases; client phases note "none" or omit).
6. API changes and OpenAPI operation IDs (`API integration` in client phases).
7. UI screens/components for client phases.
8. Ordered implementation tasks.
9. Acceptance criteria in Given/When/Then form.
10. Test plan.
11. Security checklist.
12. Quality gate.
13. Definition of done.
14. Handoff notes.

## 4. Agent rules

1. Implement only the current phase. Do not build later-phase features.
2. Follow `docs/architecture.md`, `docs/domain-model.md`, and `docs/api/openapi.yaml`.
3. Keep migrations forward-only. Never edit an applied migration.
4. Add tests with the code. A phase is not done with failing or skipped tests.
5. Never log secrets, tokens, OTP codes, full phone numbers, or full email addresses in production profiles.
6. Do not add a new dependency without a short justification in the PR.
7. Do not introduce new infrastructure services unless the spec says so.
8. Keep pull requests under roughly 800 changed lines where practical. Split refactors from behavior changes.
9. If a requirement is ambiguous or conflicts with the domain model, stop and update the spec first.
10. Update `.env.example` and deployment docs when adding configuration.
11. Update the audit log for every admin mutation.
12. Run the full phase test suite and the prior phase smoke tests before handoff.
13. Run the phase quality gate before commit. Blocking findings must be fixed or explicitly deferred in the spec.

### Phase quality gate

Run `/phase-gate <phase-id>` before a phase is committed. It runs these project subagents in order:

1. `spec-compliance` — phase spec, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Agents are defined in `.opencode/agents/`. The command is `.opencode/commands/phase-gate.md`. A phase is not complete until the gate passes or blockers are explicitly deferred in the spec.

## 5. Definition of done (global)

A phase is done when:

- All deliverables exist.
- OpenAPI matches implemented endpoints.
- Migrations apply cleanly to an empty database and to the previous phase's database.
- Unit, integration, and relevant UI tests pass locally.
- Security checklist completed.
- No secrets committed.
- `README` or runbook updated if operations changed.
- Acceptance criteria demonstrably pass.
- Phase quality gate run; blockers fixed or explicitly deferred in the spec.
- Handoff notes list known gaps and next-phase prerequisites.

## 6. Branch and PR conventions

- Branch: `phase/S1-foundation`, `phase/W3-menu-browse`, `phase/A6-orders`.
- Commit style: Conventional Commits.
- PR title: `S1: foundation and infrastructure`.
- PR description: goal, screenshots for UI, test evidence, migrations, quality-gate results, risks, rollback.
- One phase per PR unless the spec explicitly splits it.

## 7. Local development

Expected commands once S1 lands:

```bash
# server tests
cd server && ./gradlew test

# server local stack
cd infra && docker compose -f compose.dev.yml up --build

# web
cd web && pnpm install && pnpm dev
cd web && pnpm test && pnpm e2e

# android
cd android-admin && ./gradlew test
```

Local profile behavior:

- Mailgun/Textbee disabled.
- Emails/SMS logged to console.
- OTP codes printed to API logs.
- Seeded menus and fake customers allowed only in `local`.
- Cloudflare headers not required.

## 8. Phase index

| Phase | File | Scope |
|---|---|---|
| S1 | `server/S1-foundation.md` | Repo, build, DB, OpenAPI, errors, logs, tests, local compose |
| S2 | `server/S2-auth-devices.md` | Admin auth/TOTP, customer OTP, sessions, device pairing, audit |
| S3 | `server/S3-catalog-media.md` | Item templates, ingredients, allergens, customizations, image upload |
| S4 | `server/S4-menus-offerings.md` | Menus, dates, offerings, windows, publish, public menu read |
| S5 | `server/S5-orders-inventory.md` | Orders, atomic reservation, expiry, cancel/reject, bake list |
| S6 | `server/S6-notifications-payments.md` | Payment state, Mailgun, Textbee, outbox, scheduler (FCM removed in S8) |
| S7 | `server/S7-hardening-deploy.md` | Security hardening, deploy, runbooks, e2e/load tests (ops simplified in S8) |
| S8 | `server/S8-review-followups.md` | Owner review: isolation proof, submission timestamps, consent disclosure, single hostname, Mailgun/Textbee admin alerts, minimal LXC ops, follow-up routing |
| S9 | `server/S9-observability-audience-preview-test-image.md` | Prometheus metrics, admin recipient preview, self-contained dummy server image for client functional tests |
| W1 | `web/W1-scaffold.md` | React/Vite scaffold, design system, API client |
| W2 | `web/W2-auth-profile.md` | OTP login, session, profile, contact verification |
| W3 | `web/W3-menu-browse.md` | Weekly menu browse: dates/pickup, cutoff, availability, inline basket |
| W4 | `web/W4-item-detail.md` | Item detail, ingredients, allergens, customizations, cart lines |
| W5 | `web/W5-cart-checkout.md` | Basket/checkout, pickup time, details, Venmo explanation, submit |
| W6 | `web/W6-confirmation-status.md` | Order received, payment/pickup details, status, updates |
| W7 | `web/W7-history-cancel-prefs.md` | My orders, cancel, notification preferences/consent |
| W8 | `web/W8-pwa-hardening.md` | PWA, a11y, e2e, security |
| A1 | `android/A1-scaffold-pairing.md` | Compose scaffold, network, QR pairing bootstrap |
| A2 | `android/A2-login-device.md` | Login, TOTP, device management |
| A3 | `android/A3-menus-dates.md` | Overview/menu list and weekly menu editor, publish/cancel |
| A4 | `android/A4-item-templates.md` | Templates, photos, ingredients, allergens, customizations |
| A5 | `android/A5-offerings-windows.md` | Offerings, bake limits/availability, pickup & cutoff/slots |
| A6 | `android/A6-orders-bake-list.md` | Overview snapshot, pickup orders, payment/ready actions, bake list |
| A7 | `android/A7-notifications.md` | Publish, reminder, delay, cancel, rejection |
| A8 | `android/A8-push-release.md` | Update check, signing, release (FCM removed in S8) |
