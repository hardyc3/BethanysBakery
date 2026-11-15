# A6 — Orders, Payments, and Bake List

- Status: ready
- Depends on: A5, S5, S6
- Contract: `docs/api/openapi.yaml` → Admin Orders
- Next: A7

## Goal

Give Bethany a day-of-operations view: orders by bake date, accept/reject, mark paid/unpaid, mark ready/completed, delay/cancel, and a consolidated bake list.

## Non-goals

- Sending menu/reminder notifications (A7).
- Refunds (manual).
- Customer messaging beyond status notifications.

## Screens

### Orders

- Filters:
  - bake date (default today or next bake date)
  - status
  - payment status
- Summary cards: pending, accepted, ready, completed, unpaid.
- Rows:
  - order number
  - customer name
  - item count
  - total
  - pickup window
  - status + payment badges
- Pull-to-refresh; refetch on focus; manual refresh.
- Pagination.

### Order detail

- Order number, created time, status/payment.
- Customer name, email, phone with tap-to-copy/email/call.
- Pickup window/date.
- Lines with options, quantities, unit/line totals.
- Notes.
- Reasons/timestamps for accepted/ready/completed/rejected/cancelled/delay.
- Actions by state:
  - `PENDING`: Accept; Reject (reason required).
  - `ACCEPTED`: Mark ready; Mark paid/unpaid; Delay; Cancel.
  - `READY`: Mark completed; Mark paid/unpaid; Delay; Cancel.
  - `COMPLETED`: Mark paid/unpaid (correction).
  - Terminal: no status actions; payment correction only if allowed.
- Payment:
  - Paid/Unpaid toggle.
  - Method selection: Venmo, Cash, Other. Hidden customer-side; admin-only.
  - Confirmation for corrections.
- Delay:
  - warning; optional reason; sends notification.
- Cancel:
  - destructive confirmation; reason required; explains manual refund if paid.
- Accept after cutoff: show server `CUTOFF_PASSED` and refresh.

### Bake list

- Date picker.
- Total order count.
- Items:
  - item name, total quantity
  - option counts (group/option)
- Large, readable layout for kitchen use.
- Refresh.
- Excludes pending/cancelled/rejected/expired (server behavior).

## Rules

- All mutations use server response as truth; no optimistic status changes.
- Accept/reject only pending.
- Ready only accepted.
- Completed only ready.
- Payment can be corrected with audit.
- Cash is not advertised to customers.
- Destructive actions require confirmation.
- Handle 409 by refreshing and explaining.
- Show times in America/Denver.

## API integration

- `listAdminOrders`
- `getAdminOrder`
- `acceptOrder`
- `rejectOrder`
- `markOrderReady`
- `markOrderCompleted`
- `cancelAdminOrder`
- `updateOrderPayment`
- `notifyOrderDelayed`
- `getBakeList`
- `getAdminSummary`
- `sendMenuDateReminder` (A7 entry point; can live in bake list header)

## Ordered tasks

1. Orders query/filter/summary.
2. Order list UI with badges/pagination.
3. Order detail layout and copy/contact actions.
4. Accept/reject flows with reason.
5. Ready/completed flows.
6. Payment toggle/method and correction confirmation.
7. Delay and cancel flows.
8. Error/refresh handling for 409.
9. Bake list UI and date selection.
10. Tests with MockWebServer and Compose UI.
11. Seed fixtures for states.

## Acceptance criteria

- Given a bake date, then orders and summary counts load.
- Given a pending order, then Accept and Reject are available.
- Given reject with no reason, then submit is blocked.
- Given accept after cutoff, then the server error is shown and state refreshes.
- Given accepted, then Mark ready, Delay, Cancel, and payment controls are available.
- Given ready, then Mark completed is available.
- Given paid, then method and paid timestamp display.
- Given payment correction, then a confirmation is required and status updates.
- Given a terminal order, then no status actions are shown.
- Given a network failure, then the last known data remains with an error banner.
- Given a bake list, then quantities and option counts match active orders.
- Given a pending order, then it is excluded from the bake list.
- Given a cancelled/rejected/expired order, then it is excluded.

## Test plan

- Unit: action availability by status; money formatting.
- MockWebServer: list/detail, accept/reject/ready/complete/cancel, payment, delay, bake list.
- Compose UI: action dialogs, reason validation, confirmation.
- 409 handling and refresh.
- Accessibility: status labels, action grouping, large kitchen layout.

## Security checklist

- [ ] Admin auth on all requests.
- [ ] Payment/cash controls admin-only.
- [ ] Destructive actions confirmed.
- [ ] Customer PII only on detail screen.
- [ ] No PII in logs.
- [ ] Server is source of truth.
- [ ] Clipboard actions explicit.

## Quality gate

This phase is not complete until `/phase-gate A6` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Bethany can run a bake day from the app: review orders, accept/reject, mark paid, mark ready/completed, handle delays/cancellations, and read the bake list.

## Handoff notes

- A7 adds notification queue/failure views and reminder controls.
- A8 adds push deep links into order detail.
- Refunds remain manual via Venmo.
