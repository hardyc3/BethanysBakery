# A6 — Orders, Payments, Bake List, and Bakery Overview

- Status: ready
- Depends on: A5, S5, S6, S9
- Contract: `docs/api/openapi.yaml` → Admin Orders, Admin Notifications (recipient preview)
- Design: `design examples/design_exmaples.json` → **Bethany · Bakery overview**, **Bethany · Pickup orders**
- Next: A7

## Goal

Provide bakery operations views: overview snapshot/bake readiness, pickup orders management by slot with prep/payment actions, order detail actions, and bake list.

## Non-goals

- Notification campaign/reminder management UI beyond in-flow actions (A7).
- Automated refunds.

## Screens

### Overview (operations snapshot)

- Part of **Overview** tab (with A3 menu card).
- Day snapshot cards (e.g. Friday): **Orders** (18), **Items to bake** (72), **Order total** ($318).
- "Friday’s bake list" / capacity summary by item:
  - item photo/name, ordered vs limit progress (e.g. 18/24), "6 more available" or "Sold out · Orders closed".
- Next pickup reminder: "Next up: Friday pickups" with time window and order count (e.g. "4–6 pm · 18 orders to get ready") and "View pickup orders  →".
- Data from summary/bake-list/orders endpoints for selected date.

### Pickup orders

- Route/tab: **Orders** (bottom nav Overview/Menus/Orders).
- Header: "Pickup orders" with total count badge (e.g. "18 orders").
- Date selector (e.g. "Friday, November 20  ⌄").
- Filters/chips: **All 18**, **To prep 12**, **Ready 6** (map to server statuses/queues; counts reflect filtered totals).
- Grouping by pickup slot/time:
  - slot header "4:00 pm" with capacity text (e.g. "5 of 6 slots booked").
- Order cards:
  - customer name and total (e.g. "Jamie Parker", "$22").
  - order number and item summary (e.g. "#B1042 · 1 sourdough · 2 cinnamon rolls").
  - badges: payment ("Payment pending" / "Paid") and prep/status ("To prepare", "Ready for pickup").
  - actions (contextual):
    - "Confirm Venmo payment  →" when unpaid/payment needs confirmation (maps to mark paid/payment update flow as appropriate).
    - "Mark ready & notify" for orders to prepare (marks ready and triggers ready notification per server rules). Show the current recipient count from `previewNotificationRecipients` for `ORDER_READY` and allow opening the masked recipient list before confirming.
    - "Mark picked up" for ready orders (marks completed/picked up).
- Footer/help text: "Marking an order ready sends the customer\na pickup notification. Count is current and may change."
- Pull-to-refresh/manual refresh; preserve state.

### Order detail

- Full detail: customer contact (copy/call/email), lines/options/notes, totals, timestamps/reasons.
- Actions by state/payment (admin): accept/reject (reason), mark ready/completed, mark paid/unpaid (method Venmo/Cash/Other admin-only), delay/cancel with confirmations; handle 409 by refresh/explain. (Retain/align with server-supported transitions.)
- Each customer-notification action shows the server recipient count and a link to A7's masked recipient preview before confirmation: `ORDER_ACCEPTED`, `ORDER_READY`, `ORDER_DELAYED`, `ORDER_REJECTED`, `ORDER_CANCELLED`, `ORDER_EXPIRED`.
- Times in `America/Denver`.

### Bake list

- Date picker; consolidated quantities and option counts for active bakeable orders (exclude pending/cancelled/rejected/expired as per server).
- Kitchen-readable layout; refresh.

## Rules

- Server is source of truth; no optimistic status/payment mutations.
- "To prep"/"Ready"/"Picked up" UI must map to domain statuses (`PENDING`/`ACCEPTED` → prep flows, `READY`, `COMPLETED`, etc.) without inventing unsupported transitions.
- "Confirm Venmo payment" and payment badges reflect admin payment confirmation (manual Venmo/cash/other) and audit rules.
- "Mark ready & notify" combines ready transition + customer notification when supported; show failure/retry states.
- Destructive/impactful actions require confirmation; 409 conflicts refresh and explain.

## API integration

- `listAdminOrders`, `getAdminOrder`
- `acceptOrder`, `rejectOrder`, `markOrderReady`, `markOrderCompleted`
- `cancelAdminOrder`, `updateOrderPayment`, `notifyOrderDelayed`
- `previewNotificationRecipients` for order-scoped customer events.
- `getBakeList`, `getAdminSummary` (and/or equivalent endpoints for overview counts/slots)
- Slot/capacity info from pickup window/order data as provided by API.

## Ordered tasks

1. Overview snapshot cards, bake capacity list, and next-pickup CTA.
2. Pickup orders list with date selector, filter chips/counts, and slot grouping/capacity.
3. Order cards with badges and contextual actions (confirm payment, mark ready & notify, mark picked up).
4. Order detail view and full admin action set with validations/confirmations and recipient preview counts/lists.
5. Bake list view.
6. Polling/refresh and 409/error handling.
7. Tests (unit state mapping, MockWebServer, Compose UI) and fixtures.

## Acceptance criteria

- Given selected day, overview shows orders/items-to-bake/total and per-item ordered-vs-limit with available/sold-out messaging.
- Given pickup orders screen, date/filters/slot groups and "X of Y slots booked" display correctly.
- Given order in prep with unpaid, "Payment pending" and "Confirm Venmo payment" available; confirming updates payment state per server.
- Given "Mark ready & notify", recipient count/list is available from server preview, order becomes ready, notification flow is triggered/reported per API, and UI updates.
- Given ready order, "Mark picked up" completes it.
- Given bake list, counts match active bakeable orders.
- Given 409/network errors, state refreshed and user informed.

## Test plan

- Unit: filter/status mapping, capacity math, money formatting.
- MockWebServer: list/detail, payment, ready/complete/cancel/delay, bake list/summary, recipient preview.
- Compose UI: chips/slots/cards/actions, recipient count chip and preview panel, confirmations, error states.
- Accessibility: badges, action labels, readable kitchen/ops layout.

## Security checklist

- [ ] Admin auth on all requests/actions.
- [ ] Payment methods/corrections admin-only and audited.
- [ ] Destructive actions confirmed; no PII in logs.
- [ ] Server authoritative state.

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

Overview and pickup-orders flows match design and enable day-of operations: monitor capacity/sales, confirm payment, mark ready & notify, and mark picked up, with accurate bake list.

## Handoff notes

- A7 covers broader notification management/queue visibility and owns the shared recipient preview panel; A6 wires in-flow "mark ready & notify" actions and consumes that panel.
- A8 adds push deep links to orders.
- Ensure filter labels ("To prep"/"Ready") map explicitly to backend statuses in implementation/spec updates if enums differ.
