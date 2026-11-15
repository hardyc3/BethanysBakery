# W7 — Order History, Cancellation, and Notification Preferences

- Status: ready
- Depends on: W6, S5, S6
- Contract: `docs/api/openapi.yaml` → `listCustomerOrders`, `cancelCustomerOrder`, notification preferences
- Next: W8

## Goal

Let customers review past orders, cancel eligible orders, and manage email/SMS notification preferences and consent.

## Non-goals

- Refunds (manual, contact Bethany).
- Account deletion automation.
- Marketing subscriptions beyond the documented event types.

## Routes

- `/orders` — order history.
- `/orders/:orderId` — existing status detail (W6).
- `/account` — profile, cancellation policy, preferences.
- `/account/notifications` — preference editor (can also live in `/account`).

## Order history

- Requires auth.
- Paginated list (20/page), newest first.
- Each row:
  - order number
  - bake date and pickup window
  - item summary (e.g. "2 items")
  - total
  - status badge
  - payment badge
  - created date
  - link to status detail
- Filters:
  - All / Active / Completed / Cancelled, mapped to the server enum `ALL`, `ACTIVE`, `COMPLETED`, `CANCELLED`.
  - The selected filter is sent as the `filter` query parameter to `listCustomerOrders`; do not filter a full client-side page.
  - `ACTIVE` covers `PENDING`, `ACCEPTED`, `READY`.
  - `COMPLETED` covers only `COMPLETED`.
  - `CANCELLED` covers `REJECTED`, `CANCELLED`, `EXPIRED`.
  - Changing the filter resets pagination to page 1 and is reflected in the URL (`?filter=active`).
- Empty state with menu link.
- Pull-to-refresh style button; refetch on focus.
- Pagination controls with preserved state.

## Cancellation

- Cancellation available on the order detail page and history row when eligible:
  - status `PENDING` or `ACCEPTED`
  - before 00:00 America/Denver on bake date
- Show policy:
  - cancel until baking day starts
  - paid orders require a manual refund from Bethany
  - no cancellation after baking starts
- Confirm dialog:
  - warning that the order cannot be restored and stock may be released
  - optional reason field (max 500)
- On success:
  - refresh order/history
  - show cancelled state and reason
  - instruct paid customers to contact support for refund
- On 409:
  - refresh order and show the current state with an explanation.
- Do not optimistically show cancelled before the server confirms.

## Notification preferences

- Fetch `getNotificationPreferences`.
- For each event type:
  - label and short description
  - email toggle
  - SMS toggle
  - SMS disabled until consent is given
- SMS consent:
  - explicit checkbox with TCPA-style copy:
    - "I agree to receive transactional text messages from Bethany's Bakery about my orders and menus. Message frequency varies. Message and data rates may apply. Reply STOP to opt out, START to resume."
  - link to privacy policy.
  - record consent via `PUT` with `smsConsent=true`.
- `PUT` full preferences atomically; show save state and errors.
- Warning when no verified email/phone.
- STOP handling is server-side; UI shows `smsOptedOut` and lets the customer re-consent.
- Defaults from the server; do not invent local defaults.

## Profile linkage

- Link to contact verification (W2).
- Show verified/unverified badges.
- If email is unverified, email notifications may not deliver; explain.
- If phone is unverified, SMS toggle disabled.

## API integration

- `listCustomerOrders` with `filter` (`ALL` default), `page`, `size`.
- `getCustomerOrder`
- `cancelCustomerOrder`
- `getNotificationPreferences`
- `updateNotificationPreferences`
- `getCustomerProfile`

## Ordered tasks

1. Add order history query and pagination wired to the server `filter` parameter.
2. Build history rows, filters, and empty/error states.
3. Add cancellation action and confirm dialog.
4. Handle cancel success/409/401.
5. Add refund/support copy for paid cancellations.
6. Build notification preference editor.
7. Add SMS consent flow and validation.
8. Add verified-contact warnings.
9. Add tests with MSW.
10. Add Playwright history/cancel/preferences smoke.

## Acceptance criteria

- Given past orders, then history is newest first with status/payment badges.
- Given each filter, then the request sends the matching `filter` value and only matching statuses are shown: `ACTIVE` = PENDING/ACCEPTED/READY, `COMPLETED` = COMPLETED, `CANCELLED` = REJECTED/CANCELLED/EXPIRED.
- Given no orders, then an empty state links to the menu.
- Given a PENDING order before bake day, then cancel is available.
- Given an ACCEPTED order before bake day, then cancel is available and paid orders show refund instructions.
- Given a READY/COMPLETED/terminal order, then cancel is not available.
- Given bake day has started, then cancel is disabled with an explanation.
- Given a successful cancel, then the order shows CANCELLED and the reason.
- Given a 409 cancel, then the latest state is shown.
- Given SMS consent is unchecked, then SMS toggles are disabled.
- Given `smsOptedOut`, then the UI explains that texts are stopped and offers explicit re-consent.
- Given consent is checked and preferences saved, then the server state is reflected.
- Given a preference save error, then the UI restores the last server state and explains.
- Given unverified email/phone, then a warning is shown.

## Test plan

- History pagination/filtering and empty/error states.
- Cancellation eligibility matrix (status × time).
- Cancel success, 409, 401.
- Preference defaults and save/revert.
- SMS consent gating.
- Playwright: history → detail → cancel; preferences save.
- Accessibility: toggle labels, consent copy, dialog focus.

## Security checklist

- [ ] Only own orders are listed.
- [ ] Cancellation is confirmed, not optimistic.
- [ ] Consent is explicit and recorded server-side.
- [ ] No marketing opt-in bundled with transactional consent.
- [ ] Privacy policy link present.
- [ ] No PII in URLs.
- [ ] CSRF header sent.

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

Customers can review orders, cancel when allowed, and control notification channels with valid consent. W8 can run full e2e flows.

## Handoff notes

- Refunds are manual; do not imply automatic refunds.
- SMS STOP keywords are handled by S6; UI reflects server state.
- Keep preference labels aligned with `NotificationEventType` enum.
