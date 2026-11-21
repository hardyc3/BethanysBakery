# W7 — Order History, Cancellation, and Notification Preferences

- Status: ready
- Depends on: W6, S5, S6
- Contract: `docs/api/openapi.yaml` → `listCustomerOrders`, `cancelCustomerOrder`, notification preferences
- Design: `design examples/design_exmaples.json` → **Customer · Order & updates** (updates/preferences entry points) and **My orders** nav
- Next: W8

## Goal

Let customers review orders ("My orders"), cancel eligible orders, and manage notification preferences/consent with clear, accurate states.

## Non-goals

- Automated refunds (manual, contact Bethany).
- Account deletion automation.

## Routes

- `/orders` — "My orders" history.
- `/orders/:orderId` — status/detail (W6).
- `/account` and/or `/account/notifications` (and/or **Updates** tab) — preferences/consent and profile links; align with W6/W3 nav.

## My orders / order history

- Requires auth; paginated (20/page), newest first.
- Row/card: order number, bake date/pickup time, item summary, total, status and payment badges, link to detail.
- Filters mapped to server `filter` (`ALL` default, `ACTIVE`, `COMPLETED`, `CANCELLED`); sent as query param (no full client-side filter). Reset to page 1 on change; reflect in URL.
  - `ACTIVE`: `PENDING`, `ACCEPTED`, `READY`
  - `COMPLETED`: `COMPLETED`
  - `CANCELLED`: `REJECTED`, `CANCELLED`, `EXPIRED`
- Empty state links to Menu.
- Refetch on focus; pull-to-refresh/manual refresh.

## Cancellation

- Available for eligible orders (detail/history) per policy: `PENDING` or `ACCEPTED` before `00:00 America/Denver` on bake date (server authoritative).
- Confirm dialog with policy: cancel cutoff, no restore, inventory may release, paid orders require manual refund from Bethany; optional reason (max 500).
- On success refresh and show cancelled state/reason; on 409 refresh and explain current state. No optimistic cancel.

## Notification preferences (align with design)

- Design shows friendly toggles: **Order ready**, **New weekly menus**, **Before a baking day** with short descriptions and phone context (e.g. "Send text updates to <phone>").
- Map to real server `NotificationEventType`s/preferences; present accurate labels/descriptions and channels (email/SMS) per capability.
  - Do not invent event types; if UI groups/types differ, map clearly and document (update contract/spec only when intentionally changed).
- SMS consent/verification:
  - explicit consent required; show TCPA-style copy and privacy policy link.
  - SMS toggles disabled until consent and verified phone where required.
  - reflect `smsOptedOut`/STOP state and allow explicit re-consent flow.
  - `PUT` with `smsConsent=false` revokes server-side consent; send `true` to retain/renew consent, otherwise START cannot clear a prior STOP.
- `PUT` preferences atomically; show save state/errors and revert to server state on failure.
- Warn when email/phone unverified.

## API integration

- `listCustomerOrders` (`filter`, `page`, `size`)
- `getCustomerOrder`
- `cancelCustomerOrder`
- `getNotificationPreferences`
- `updateNotificationPreferences`
- `getCustomerProfile`

## Ordered tasks

1. Build "My orders" list, filters, pagination, empty/error states.
2. Add navigation between Menu/My orders/Updates and order detail.
3. Implement cancellation eligibility, dialog, and 409/success handling.
4. Implement preference UI matching design labels mapped to server events/channels.
5. Implement SMS consent, verification warnings, opt-out/re-consent states.
6. Persist preferences with save/revert behavior.
7. Add tests (MSW) and Playwright smoke.

## Acceptance criteria

- Given orders, "My orders" shows newest first with correct badges and filters/page behavior.
- Given empty history, CTA to Menu shown.
- Given eligible order, cancel flow works with confirmation and correct server-result states; ineligible orders cannot cancel.
- Given design toggle labels, each maps to correct preference state and persists (or shows accurate read-only/unavailable state).
- Given no consent/unverified phone or opted-out SMS, SMS controls behave correctly with clear explanations.
- Given save error, UI reverts and explains.
- Given 360px/mobile, controls accessible.

## Test plan

- History pagination/filter mapping and states.
- Cancellation eligibility matrix and 409 handling.
- Preference load/save/revert, consent gating, opt-out display.
- Playwright: My orders → detail/cancel; Updates/preferences save.
- Accessibility: list semantics, dialogs, toggles, consent copy.

## Security checklist

- [ ] Own orders only; confirmed (not optimistic) cancel.
- [ ] Explicit SMS consent recorded server-side; privacy policy linked.
- [ ] No PII in URLs; CSRF/auth per API.

## Quality gate

This phase is not complete until `/phase-gate W7` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

"My orders" and Updates/preferences reflect the design and support safe cancellation and accurate, consent-aware notification settings.

## Handoff notes

- Coordinate toggle-to-event mapping with S6/W6 so labels match domain events.
- Refunds remain manual.
- Keep preference labels aligned with enums once mapping is finalized.
