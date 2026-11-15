# W6 — Order Confirmation, Venmo Instructions, and Status

- Status: ready
- Depends on: W5, S5, S6
- Contract: `docs/api/openapi.yaml` → `getCustomerOrder`, order status
- Next: W7

## Goal

Show a clear order confirmation, Venmo payment instructions, and the live order status through acceptance, ready, delay, cancellation, and payment changes.

## Non-goals

- Order history list (W7).
- Cancelling from history (W7; the detail page may include the same action).
- Automated payment verification.

## Routes

- `/orders/:orderId/confirmation` — post-checkout success.
- `/orders/:orderId` — status detail (same component, different heading).
- Both require auth and ownership.

## Confirmation content

- Success heading and order number.
- Pickup date, window, and address/maps link.
- Item lines with options, quantities, line totals.
- Order total.
- Payment block, sourced only from `PaymentInstructions`:
  - `venmoHandle` and `venmoDisplayName`.
  - `amountCents` rendered with `Intl.NumberFormat` in USD.
  - `note` / reference: order number.
  - Copy buttons for handle/amount/note.
  - Venmo deep link when available; fallback to venmo.com profile URL.
  - Instructions: "Send payment, then Bethany will mark it paid." Do not mention cash at pickup; it is an admin-only fallback for known customers.
  - Payment status badge: UNPAID/PAID.
- Status timeline:
  - Placed → Accepted → Ready → Completed, plus terminal branches.
  - Show timestamps when present.
  - Show rejection/cancellation reason.
  - Show delay notice/reason when set.
- Ready state:
  - prominent "Ready for pickup" with maps link.
  - pickup window reminder.
- Rejected/cancelled:
  - clear reason and support contact.
  - no Venmo prompt for terminal unpaid rejected/cancelled orders.
- Notification expectations:
  - email/SMS toggles live in W7 preferences.
  - show "We'll email/text you when it's ready" based on profile/preferences if available.

## Polling

- TanStack Query key `['order', orderId]`.
- `refetchInterval`:
  - 30 seconds for `PENDING`/`ACCEPTED`.
  - 60 seconds for `READY`.
  - stop for `COMPLETED`, `REJECTED`, `CANCELLED`, `EXPIRED`.
- Refetch on focus.
- Keep previous data to avoid flicker.
- If 404/403, show not-found/unauthorized state.

## Status mapping

| Status | UI |
|---|---|
| PENDING | "Waiting for Bethany to accept" |
| ACCEPTED | "Accepted — please send Venmo payment" |
| READY | "Ready for pickup" + maps |
| COMPLETED | "Picked up — thank you" |
| REJECTED | "Not accepted" + reason |
| CANCELLED | "Cancelled" + reason |
| EXPIRED | "Expired — not accepted before cutoff" |

Payment status is independent; show both.

## Ordered tasks

1. Build order query and ownership handling.
2. Build confirmation header and summary.
3. Build Venmo payment card with copy/deep link.
4. Build status timeline and terminal states.
5. Add polling policy and focus refetch.
6. Add maps link and pickup details.
7. Add delay/rejection/cancellation reasons.
8. Add tests with MSW for each status.
9. Add Playwright confirmation/status smoke.

## Acceptance criteria

- Given a new order, then the confirmation shows the order number, server total, and `venmoHandle`, `venmoDisplayName`, amount, and note.
- Given a copy button, then the value is copied and an accessible confirmation is announced.
- Given `PENDING`, then the status explains acceptance is pending and polling continues.
- Given `ACCEPTED`, then Venmo instructions are prominent and payment status shows UNPAID.
- Given `READY`, then a maps link and ready message appear.
- Given `COMPLETED`, then polling stops and the terminal state renders.
- Given `REJECTED`, then the reason and support contact appear and Venmo is not requested.
- Given `EXPIRED`, then the cutoff explanation appears.
- Given `PAID`, then the payment badge updates.
- Given a delayed order, then the delay notice/reason appears.
- Given another customer's order ID, then access is denied.

## Test plan

- Component tests for each status/payment combination.
- Polling start/stop with fake timers.
- Copy/deep link behavior.
- Ownership/403 state.
- Maps URL correctness.
- Playwright: checkout → confirmation → status update via API fixture.
- Accessibility: timeline semantics, live region for status changes.

## Security checklist

- [ ] Only the owner can view the order.
- [ ] Venmo link uses `venmoHandle`/`venmoDisplayName` from the server, not user input.
- [ ] No payment credentials collected.
- [ ] Copy actions do not leak other data.
- [ ] External links use `noopener noreferrer`.
- [ ] Polling stops on terminal states.

## Quality gate

This phase is not complete until `/phase-gate W6` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

The post-order experience is clear, payment instructions are unambiguous, status changes are visible without reload, and terminal states are handled.

## Handoff notes

- W7 uses the same order detail component for history.
- If notification preferences are unavailable, use generic "we'll notify you" copy.
- The server remains the source of truth for payment/status.
