# W5 — Cart and Checkout

- Status: ready
- Depends on: W4, S5
- Contract: `docs/api/openapi.yaml` → `createOrder`, public menu read
- Next: W6

## Goal

Review the cart, confirm the pickup window, handle availability changes, and submit an order with a clear success or failure path.

## Non-goals

- Payment confirmation (W6).
- Order history (W7).
- Saved payment methods.

## Routes

- `/cart`
- Checkout is a single review page; no multi-step wizard.

## Screen

1. Cart lines:
   - photo, name, selected options, notes
   - unit price, quantity stepper, line total
   - remove line
   - availability warning if the offering changed
2. Pickup window:
   - selected date/window summary
   - change window link back to menu with date context
   - capacity/cutoff status
3. Contact:
   - name, email, phone from profile
   - link to `/account` if missing/unverified
4. Order notes (max 500).
5. Totals:
   - subtotal
   - total (no tax/fees)
   - server-confirmed total after submit
6. Submit button:
   - disabled when empty, profile incomplete, cutoff passed, or submitting.
   - shows "Place order".
7. Policy notes:
   - Venmo payment instructions follow on the next screen.
   - cancellation window.
   - pickup address/maps link.

## Checkout flow

1. Validate cart shape locally.
2. Fetch the latest menu date/offering availability.
3. Compare:
   - offering still active and assigned to the selected window
   - available quantity >= cart quantity
   - window capacity > 0
   - cutoff not passed
4. If changed:
   - show a blocking dialog listing affected lines.
   - options: update quantities, remove sold-out lines, or return to menu.
   - never silently reduce an order.
5. Submit `POST /api/v1/orders` exactly once:
   - generate a client `idempotency_key` (UUID) when checkout begins; reuse the same key for retries of the same submission.
   - disable button and use an in-memory guard.
   - do not auto-retry mutations. If user taps retry after unknown/network failure, send the same `idempotency_key` so the server returns the existing order instead of creating duplicates.
6. Handle responses:
   - `201`: clear cart, navigate to `/orders/:id/confirmation`.
   - `409 SOLD_OUT`: mark affected line(s), explain, keep cart.
   - `409 WINDOW_FULL`: force window reselection.
   - `409 CUTOFF_PASSED`: disable submit, link to menu.
   - `409 CUSTOMER_LIMIT_EXCEEDED`: show limit details and allow quantity edit.
   - `400 INVALID_CUSTOMIZATION`: remove invalid options and explain.
   - `401`: refresh once, then redirect to login with return-to.
   - `429`: show retry wait.
   - network/5xx: keep cart, show retry, never assume success.
7. After a successful order, refetch menu availability when returning to the menu.

## Profile guard

- Name required.
- At least one verified contact.
- If not satisfied, show an inline callout and link to `/account`.
- Preserve cart through login/profile edits.

## Cart editing

- Quantity changes revalidate against last-known availability.
- Removing last line shows empty cart state.
- Empty cart links back to menu.
- Cart remains scoped to one date/window.

## API integration

- `createOrder`
- `getPublicMenuDate`
- `getPublicOffering`
- `getCustomerProfile`

## Ordered tasks

1. Build cart page and line components.
2. Add quantity/remove/edit behavior.
3. Build review summary, window/contact sections, notes, totals.
4. Implement pre-submit availability revalidation.
5. Implement changed-cart dialog and recovery actions.
6. Implement submit with duplicate-click protection.
7. Map all Problem codes to actionable UI.
8. Clear cart on success and navigate to confirmation.
9. Add tests with MSW for every error code.
10. Add Playwright checkout happy path and sold-out race.

## Acceptance criteria

- Given an empty cart, then an empty state links to the menu.
- Given a missing name, then checkout is blocked with a profile link.
- Given no verified contact, then checkout is blocked with a profile link.
- Given availability dropped below cart quantity, then a blocking dialog appears and no order is submitted.
- Given a valid cart, when submitted, then exactly one order is created and the cart is cleared.
- Given a double click, then only one order is created.
- Given a retry after network/timeout failure, then the same `idempotency_key` is sent and the server returns the existing order if it was created.
- Given `SOLD_OUT`, then the affected line is identified and the cart remains.
- Given `WINDOW_FULL`, then the customer is prompted to choose another window.
- Given `CUTOFF_PASSED`, then submit is disabled and ordering is closed.
- Given `CUSTOMER_LIMIT_EXCEEDED`, then the limit is explained.
- Given a network failure, then the cart remains and a retry is offered.
- Given a 401 with a valid refresh, then the order is retried once after refresh with the same `idempotency_key`.
- Given success, then the confirmation page shows the order number and total from the server.

## Test plan

- Component tests for cart editing and totals.
- Availability revalidation and dialog behavior.
- Each Problem code mapping.
- Double-submit guard.
- 401 refresh-and-retry once.
- Playwright: menu → item → cart → checkout → confirmation.
- Playwright: simulate sold-out between browse and submit.
- Accessibility: form labels, dialog focus trap, error announcements.

## Security checklist

- [ ] Totals/prices are never trusted from the client.
- [ ] No card/payment data collected.
- [ ] CSRF header sent.
- [ ] No order submission on page load.
- [ ] Notes escaped.
- [ ] Login return path is local-only.

## Quality gate

This phase is not complete until `/phase-gate W5` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Checkout handles real inventory races gracefully, creates exactly one order, and W6 receives a stable order ID. Tests cover all documented error codes.

## Handoff notes

- W6 owns the confirmation/status screen and Venmo instructions.
- Server commits inventory; the client must treat `201` as final.
- If the server later adds an idempotency key, send it from this phase's submit path.
