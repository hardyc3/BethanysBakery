# W6 — Order Confirmation, Payment, and Status/Updates

- Status: ready
- Depends on: W5, S5, S6
- Contract: `docs/api/openapi.yaml` → `getCustomerOrder`, `PaymentInstructions`, notification preferences (as applicable)
- Design: `design examples/design_exmaples.json` → **Customer · Order & updates**
- Next: W7

## Goal

Show order received/confirmation with pickup details, clear Venmo payment steps, live status, and friendly post-order updates/preferences.

## Non-goals

- Full order history list filtering UX beyond needed links (W7).
- Refund processing.

## Routes

- `/orders/:orderId/confirmation` — post-checkout "Order received".
- `/orders/:orderId` — status/detail (shared component/structure where practical).
- Authenticated and ownership-enforced.

## Screen

### Order received header

- Brand header with back/navigation context.
- "Order received" context, order badge "ORDER #<number>" (e.g. `#B1042`).
- Celebratory heading (e.g. "Your Friday just\ngot sweeter.") and thank-you line with customer name and item count (e.g. "Thanks, Jamie. Your 3 bakes are reserved!").

### Payment pending / payment block

- When unpaid: "Payment pending" badge/card.
- "One last thing: send $22" (server total).
- "Pay @Bethany-Bakes on Venmo.\nAdd “<order #> · <name>” in the note." (handle/display name/amount/note from `PaymentInstructions` where available).
- Primary action: "Open Venmo  ↗" (deep link if available, fallback URL) plus copy affordances as needed.
- "Your order will show as paid once Bethany confirms." (manual confirmation).
- When paid: payment-complete state/badge; hide pay prompt.

### Pickup summary

- "Your pickup" card: date/time (e.g. "Friday, November 20 · 4:00 pm"), location/address (e.g. "Bethany’s home bakery\n124 Maple Lane · Front porch pickup") as provided/allowed, divider, item summary (e.g. "1 sourdough loaf  ·  2 cinnamon rolls").
- Maps link when appropriate.

### Status

- Status timeline/badges for Placed/Accepted/Ready/Completed and terminal states (Rejected/Cancelled/Expired) with timestamps/reasons/delay notices as available.
- Prominent ready state and pickup reminders; stop polling on terminal states.
- Design emphasizes received/payment/pickup; detailed timeline may live in detail view but must remain clear and accessible.

### Friendly updates/preferences (per design)

- Section: "Fresh bakes. Friendly reminders."
- Context: "Send text updates to <phone>" when applicable.
- Toggles shown: **Order ready** ("Know when it’s time to pick up."), **New weekly menus** ("Get first pick of the next batch."), **Before a baking day** ("A reminder to order the day before.").
  - Reflect and update real notification preferences/consent where supported (see W7/S6); if a setting is unavailable, show accurate state/copy (do not fake persistence).
  - SMS requires verified number and consent per policy.

### Footer/actions

- "Back to this week’s menu" primary secondary action.
- "Questions? Text Bethany" support/help affordance.

## Polling

- Query key `['order', orderId]`; refetch on focus.
- Intervals: ~30s for `PENDING`/`ACCEPTED`, ~60s for `READY`, stop for terminal states.
- Keep previous data; handle 403/404 clearly.

## Status mapping

| Status | UI |
|---|---|
| PENDING | waiting/placed (and payment pending as applicable) |
| ACCEPTED | accepted; payment instructions if unpaid |
| READY | ready for pickup + location/maps/time |
| COMPLETED | picked up/completed |
| REJECTED | not accepted + reason |
| CANCELLED | cancelled + reason |
| EXPIRED | expired/cutoff explanation |

Payment status shown independently (UNPAID/PAID).

## API integration

- `getCustomerOrder`, server-provided `PaymentInstructions`.
- Notification preference endpoints as needed for toggles (coordinate with W7).
- External links: Venmo/maps with safe `noopener noreferrer`.

## Ordered tasks

1. Build confirmation/received layout (badge, hero, thank-you).
2. Build payment pending/paid card with Venmo deep link/copy and manual-confirm copy.
3. Build pickup summary (time, address reveal, items).
4. Build status/timeline and terminal states with polling.
5. Build updates/preferences toggles wired to real prefs/consent states.
6. Add footer actions and support contact.
7. Add tests (MSW) per status/payment and Playwright smoke.

## Acceptance criteria

- Given new order, confirmation shows order number, thank-you, items/total context, and pickup time/date.
- Given unpaid, shows "Payment pending", amount, Venmo handle/note instructions, "Open Venmo", and manual confirmation note.
- Given paid, payment-pending prompt replaced by paid state.
- Given pickup details, address/location and item summary shown as in design.
- Given status changes, UI updates via polling and stops on terminal.
- Given toggles, shown states match server preferences/consent; changes persist when supported with correct SMS consent/verification rules.
- Given back-to-menu/questions actions, navigation/help works.
- Given ownership violation, access denied.

## Test plan

- Component tests for received/payment/pickup/status combinations and toggle states.
- Polling start/stop, copy/deep-link behavior.
- MSW for order + preference APIs.
- Playwright: checkout → received → status/payment update.
- Accessibility: headings, badges, live updates, toggle labels, link names.

## Security checklist

- [ ] Owner-only access.
- [ ] Venmo handle/note/amount from server, not user input.
- [ ] No payment credentials collected.
- [ ] External links safe; no PII in URLs.
- [ ] Preference/consent changes explicit and server-validated.

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

Post-order experience matches design: warm received state, clear manual Venmo/payment flow, pickup details, live status, and accurate updates/preferences controls.

## Handoff notes

- W7 owns full history and deeper preference management/cancellation flows; share components where possible.
- Confirm exact notification event mapping for "Order ready / New weekly menus / Before a baking day" against `NotificationEventType` and preference model.
- Pickup address visibility rules must follow server/domain policy.
