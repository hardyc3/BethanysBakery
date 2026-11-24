# W5 — Cart and Checkout

- Status: ready
- Depends on: W4, S5
- Contract: `docs/api/openapi.yaml` → `createOrder`, public menu read
- Design: `design examples/design_exmaples.json` → **Customer · Checkout**
- Next: W6

## Goal

Review basket, choose/confirm pickup time, collect required customer details, explain Venmo/manual payment, and submit an order with clear success/failure handling.

## Non-goals

- Collecting in-app payment (Venmo handled outside app).
- Payment confirmation/status timeline (W6).
- Order history (W7).

## Routes

- `/cart` (basket/checkout view).
- Single-page checkout flow (review → details → place order) consistent with design.

## Screen

### Top bar/context

- Brand header (logo/name) and "Your basket" with back action ("← Back").

### Basket summary

- Heading: "Save your slice." (brand tone).
- Context: "<Day, Month Date> · <n> items" for selected bake day.
- Basket items card/list:
  - lines like "1 × Country sourdough" with line amount, "2 × Cinnamon rolls" with amount.
  - divider and **Total** (e.g. `$22`).
  - allow edit quantity/remove (and/or return to menu) per cart rules.

### Pickup selection

- "Choose a pickup time" for selected bake day/location context (e.g. "Friday, Nov 20 · Bethany’s home bakery").
- Pickup time options as selectable chips/slots (e.g. `4:00 pm`, `4:30 pm`, `5:00 pm`) mapped to available pickup windows/slots from server.
  - disabled states for full/closed/cutoff slots as applicable.
  - if design shows time slots finer than server windows, map slots to the correct `pickupWindowId` and document/align in API; do not invent server-unsupported slots.
- Helper text: "Pickup address is shared after ordering." (exact pickup address shown post-order per W6 unless policy changes).

### Customer details

- "Your details".
- Fields shown in design: **Full name** (e.g. "Jamie Parker"), **Mobile number** (e.g. "(555) 014-0826").
  - prefill from authenticated profile when available.
  - allow entry/confirmation as needed for ordering/contact; persist to profile per W2 contract/rules where appropriate.
  - validate required fields before submit (name and reachable contact per domain rules).

### Payment explanation

- Card/callout: "A small-batch way to pay".
- Copy: "Place your order, then send $22 to\n@Bethany-Bakes on Venmo." (amount/handle dynamic from order/server config where available).
- "Bethany confirms your payment manually."
- No in-app payment collection.

### Agreement and submit

- Agreement text: "By ordering, you agree to pick up during your selected time. Please tell Bethany about any allergies."
- Sticky bottom CTA: "Place order  ·  $22" (disabled when invalid/submitting/closed).
- Footer note: "No payment is collected in this app."

## Checkout flow

1. Validate cart shape and required details locally.
2. Fetch latest availability for selected date/window/offerings.
3. Compare: active/assigned offerings, available quantity, window/slot capacity, cutoff not passed.
4. If changed, show blocking dialog listing affected lines; options to update quantities, remove sold-out lines, or return to menu. Never silently reduce.
5. Submit `POST /api/v1/orders` once with generated `idempotency_key` (UUID) reused on retry of same submission; disable CTA and guard.
6. Handle responses:
   - `201`: clear cart, navigate to order confirmation/received screen (W6).
   - `409 SOLD_OUT` / `409 WINDOW_FULL` / `409 CUTOFF_PASSED` / `409 CUSTOMER_LIMIT_EXCEEDED` / `400 INVALID_CUSTOMIZATION`: show actionable errors, keep cart, allow fix.
   - `409 OFFERING_NOT_AVAILABLE` / `409 MENU_NOT_AVAILABLE` / `409 WINDOW_NOT_AVAILABLE`: these mean the menu/window/offering changed since the basket was built (or the requested IDs were stale). Re-fetch availability, show the blocking change dialog from step 4, and never silently drop or reduce a line. Treat missing IDs surfaced as 404 `NOT_FOUND` the same way.
   - `401`: refresh once then login with return-to.
   - `429`: show retry wait.
   - network/5xx: keep cart, retry with same idempotency key when appropriate; never assume success.
7. On return to menu, refetch availability.

## Profile/auth guard

- User must be able to place order per auth/profile requirements (W2). If login or missing required/verified contact blocks order, prompt inline with clear path (sign in / complete details) while preserving cart.
- Do not lose cart through auth/profile steps.

## Ordered tasks

1. Build basket/cart review UI and edit controls.
2. Implement pickup day/time slot selection mapped to server windows.
3. Implement customer details form with prefill/validation.
4. Implement Venmo/payment explanation and agreement copy.
5. Add pre-submit availability revalidation and change dialogs.
6. Implement order submit with idempotency and response handling.
7. Add loading/disabled/error states and retry behavior.
8. Add tests (MSW), cart/slot mapping tests, and Playwright checkout smoke.

## Acceptance criteria

- Given cart items, basket shows item lines, per-line amounts, and total.
- Given bake day, user can choose a pickup time slot; full/closed/cutoff slots not selectable.
- Given missing/invalid required details, Place order disabled with guidance.
- Given valid basket/details/slot, submit creates order once and navigates to confirmation.
- Given availability/capacity/cutoff changes, blocking explanation shown and no silent reduction.
- Given submit failure after unknown state, retry uses same idempotency key.
- Given "No payment is collected in this app" and Venmo copy, user understands manual payment step.
- Given 360px width, layout readable and CTA accessible.

## Test plan

- Unit: totals, slot-to-window mapping, idempotency key reuse, validation.
- Component: basket edits, slot states, details prefill/validation, error dialogs.
- MSW: 201 and all 4xx/409/429/5xx cases, including `OFFERING_NOT_AVAILABLE`, `MENU_NOT_AVAILABLE`, `WINDOW_NOT_AVAILABLE`, and 404 `NOT_FOUND` availability races.
- Playwright: menu → basket → pickup time → details → place order.
- Accessibility: form labels, chip selection, dialog focus, error announcements.

## Security checklist

- [ ] CSRF/auth handled per API.
- [ ] Idempotency key scoped to submission attempt/retry.
- [ ] No PII in URLs; details handled per privacy rules.
- [ ] Client totals display-only; server authoritative.
- [ ] No in-app payment data collected.

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

Checkout matches design: basket review, pickup time choice, customer details, Venmo/manual-payment explanation, agreement, and reliable order submission with safe error handling.

## Handoff notes

- S8: send optional `client_submitted_at` on order create for diagnosis only, and
  generate a fresh idempotency key after the user edits the basket. Same-key
  replay still returns the original order with `200`; the client timestamp never
  changes validation, cutoff, or stock.
- Post-order confirmation, Venmo deep link/copy details, pickup address reveal, and status live in W6.
- Slot granularity must align with server pickup-window model; update OpenAPI/domain if finer-grained slots are required.
- Keep design copy tone consistent ("Save your slice", small-batch/manual payment).
