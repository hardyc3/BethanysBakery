# S5 — Orders, Inventory, Expiry, and Bake List

- Status: ready
- Depends on: S4
- Contract: `docs/api/openapi.yaml` → Customer orders, Admin Orders
- Next: S6

## Goal

Place orders against a published bake date and pickup window, enforce availability atomically, manage the order lifecycle, expire unaccepted orders at cutoff, release stock on cancellation/rejection, and provide the admin bake list.

## Non-goals

- Payment marking (S6).
- Email/SMS/push delivery (S6). Publish domain events through an interface; S6 implements them.
- Customer web UI (W5/W6/W7).
- Admin Android UI (A6).

## Data model — `V5__orders.sql`

Create exactly as described in `docs/domain-model.md`:

- `orders`
- `order_line`
- `order_line_option`
- `daily_order_sequence`
- `outbox_event` — created here (not in S6's `V6`) because the order transaction must enqueue `OrderCreated` in the same commit; S6 reuses it. Schema and index are in `docs/domain-model.md`.

`orders` includes all lifecycle columns from the domain model, including `payment_method`, `paid_at`, `delay_notified_at`, `delay_reason`, `inventory_released_at`, and `idempotency_key`. S6 does not need to alter the order table.

Constraints/indexes:

- Unique `order_number`.
- Partial unique index on `idempotency_key` where `idempotency_key IS NOT NULL`.
- `quantity > 0`.
- `line_total_cents >= 0`.
- `subtotal_cents = total_cents` for now; no tax/fees.
- `status` and `payment_status` checks.
- Indexes from `docs/domain-model.md`, especially `orders(status, expires_at)`.

## Order creation

Endpoint: `POST /api/v1/orders`.

Idempotency:

- `CreateOrderRequest` accepts an optional `idempotency_key` (string).
- If `idempotency_key` is provided, the server first checks `orders` for an existing order with that key belonging to the current customer. If found, return that existing order (200 OK, same `Order` shape) with no new counters, rows, or events. Do not create a duplicate.
- If not found, proceed below and store the key on the new order.

Customer must:

- Be authenticated.
- Have a non-blank name.
- Have at least one verified contact channel.

Request:

- one `menuDateId`
- one `pickupWindowId`
- 1–50 lines
- each line: `offeringId`, `quantity` (1–99), optional `optionIds`, optional notes
- optional order notes, max 500 chars
- optional `idempotency_key`

Validation, all before any counter update:

1. Menu date exists, belongs to a published menu, and is `PUBLISHED`. Else 409 `MENU_NOT_AVAILABLE` (unknown ID → 404 `NOT_FOUND`).
2. Window exists, is `ACTIVE`, belongs to the date. Else 409 `WINDOW_NOT_AVAILABLE` (unknown ID → 404 `NOT_FOUND`).
3. `now < window.startAt - cutoff`. Else 409 `CUTOFF_PASSED`. Cutoff hours come from `ORDER_CUTOFF_HOURS` (default 12).
4. Offering exists, is `ACTIVE`, belongs to the date, and is assigned to the window. Else 409 `OFFERING_NOT_AVAILABLE`.
5. Quantity positive.
6. Per-customer limit: see the locked check inside the reservation transaction below.
7. Option IDs:
   - belong to the offering's customization snapshot
   - options are available
   - no duplicate option IDs
   - required/min/max selections satisfied per group
   - unknown or cross-group option → 400 `INVALID_CUSTOMIZATION`
8. Compute line total:
   - `line_total = (unit_price + sum(selected option deltas)) * quantity`
   - `subtotal = sum(line totals)`
   - `total = subtotal`
9. Normalize duplicate lines: merge quantities only when the offering, the sorted set of option IDs, and line notes are identical. Different customizations or notes remain separate lines.

Reservation transaction (single transaction, deadlock-safe):

1. `UPDATE pickup_window SET reserved_orders = reserved_orders + 1 WHERE id = :id AND reserved_orders < max_orders`. Zero rows → 409 `WINDOW_FULL`.
2. Lock the customer's active order lines for this offering+bake date:
   `SELECT ol.id FROM order_line ol JOIN orders o ON o.id = ol.order_id WHERE o.customer_id = :customerId AND ol.offering_id = :offeringId AND o.menu_date_id = :menuDateId AND o.status IN ('PENDING','ACCEPTED','READY') FOR UPDATE`.
   Re-check per-customer limit: sum quantities across those locked lines plus the new quantity must be <= `maxPerCustomer` when set. Else 409 `CUSTOMER_LIMIT_EXCEEDED`. Roll back all counters.
3. For each distinct offering in ascending ID order:
   `UPDATE offering SET reserved_count = reserved_count + :qty WHERE id = :id AND reserved_count + :qty <= quantity_limit`. Zero rows → 409 `SOLD_OUT`.
4. Generate order number under `daily_order_sequence` row lock:
   `BB-<bakeDate yyyyMMdd>-<next_value padded to 4>`, increment.
5. Insert order (`PENDING`, `UNPAID`), lines, selected options, snapshots. Store `idempotency_key` when provided.
6. Set `expiresAt = window.startAt - cutoff`.
7. Write audit `ORDER_CREATED`.
8. Write an `outbox_event` row for `OrderCreated` in this same transaction (`status=PENDING`, `available_at=now`, idempotency key `order:{orderId}:created`). Do not publish after commit as the durable path; the outbox row is the source of truth.

Any failure rolls back all counters and rows.

## Order lifecycle

Statuses: `PENDING`, `ACCEPTED`, `READY`, `COMPLETED`, `REJECTED`, `CANCELLED`, `EXPIRED`.

### Admin accept

- `POST /admin/v1/orders/{id}/accept`
- Only `PENDING`.
- Only before cutoff. After cutoff → 409 `CUTOFF_PASSED`.
- Sets `accepted_at`, status `ACCEPTED`.
- No counter changes.
- Audit `ORDER_ACCEPTED`.
- Event `OrderAccepted`.

### Admin reject

- `POST /admin/v1/orders/{id}/reject`
- Only `PENDING`.
- Reason required.
- Sets `REJECTED`, reason, releases window + offering counters exactly once.
- Audit `ORDER_REJECTED`.
- Event `OrderRejected`.

### Admin ready

- `POST /admin/v1/orders/{id}/ready`
- Only `ACCEPTED`.
- Sets `READY`, `ready_at`.
- Audit `ORDER_READY`.
- Event `OrderReady`.

### Admin complete

- `POST /admin/v1/orders/{id}/completed`
- Only `READY`.
- Sets `COMPLETED`, `completed_at`.
- No counter change.
- Audit `ORDER_COMPLETED`.
- Event `OrderCompleted` (no customer notification required).

### Customer cancel

- `POST /api/v1/orders/{id}/cancel`
- Only own order.
- Allowed statuses `PENDING` or `ACCEPTED`.
- Allowed only before `bake_date 00:00` in `America/Denver`. Else 409 `CANCELLATION_CLOSED`.
- Sets `CANCELLED`, `cancelled_by=CUSTOMER`, optional reason, releases counters.
- Audit `ORDER_CANCELLED_BY_CUSTOMER`.
- Event `OrderCancelled`.

### Admin cancel

- `POST /admin/v1/orders/{id}/cancel`
- Allowed statuses `PENDING`, `ACCEPTED`, `READY`.
- Reason required.
- Releases counters (idempotent).
- Audit `ORDER_CANCELLED_BY_ADMIN`.
- Event `OrderCancelled`.

### Expiry

- Scheduled job every 60 seconds.
- Find `PENDING` orders with `expires_at <= now` and `status='PENDING'`.
- Atomically transition to `EXPIRED`, set `cancelled_by=SYSTEM`.
- Release counters exactly once.
- Audit `ORDER_EXPIRED`.
- Event `OrderExpired`.
- Idempotent under repeated runs/concurrent instances (single instance expected, but use conditional update).

### Menu/date cancellation propagation

- Implement the S4 cancellation hook.
- When a menu date is cancelled, transition all `PENDING`, `ACCEPTED`, `READY` orders on that date to `CANCELLED` with `cancelled_by=SYSTEM` and the date reason.
- Release counters.
- Publish `MenuDateCancelled` event per affected order/date.
- Menu cancellation applies to all its dates.

### Inventory release

- Central `InventoryService.release(orderId)`.
- Atomically claim release with `UPDATE orders SET inventory_released_at = now() WHERE id = :id AND inventory_released_at IS NULL`. Only the transaction that updates one row decrements counters.
- Never allow counters below zero.

## Reads

- `GET /api/v1/me/orders`: own orders, newest first, paginated. Optional `filter`:
  - `ALL` (default): every status.
  - `ACTIVE`: `PENDING`, `ACCEPTED`, `READY`.
  - `COMPLETED`: `COMPLETED` only.
  - `CANCELLED`: `REJECTED`, `CANCELLED`, `EXPIRED`.
- `GET /api/v1/orders/{id}`: own order only; include payment instructions (S6 fills Venmo env values; S5 can build from config).
- `GET /admin/v1/orders?date=&status=&paymentStatus=`: paginated, date optional.
- `GET /admin/v1/orders/{id}`.
- `GET /admin/v1/summary?date=YYYY-MM-DD`: counts for one bake date:
  - `pending`, `accepted`, `ready`, `completed` by status.
  - `unpaid` counts active orders (`PENDING`, `ACCEPTED`, `READY`, `COMPLETED`) with `payment_status=UNPAID`.
- Order response includes snapshots, lines, options, totals, status timestamps, expiry.

## Bake list

`GET /admin/v1/bake-list?date=YYYY-MM-DD`

- Include orders in `ACCEPTED`, `READY`, `COMPLETED` for that bake date. Exclude `PENDING`, `REJECTED`, `CANCELLED`, `EXPIRED`.
- Aggregate quantity by offering.
- Aggregate selected option counts by group/option.
- Include `orderCount`.
- Sorted by item name, then group/option name.
- No PII beyond item quantities.

## Audit and events

Audit every admin mutation and system expiry/cancellation:

- `ORDER_CREATED`, `ORDER_ACCEPTED`, `ORDER_REJECTED`, `ORDER_READY`, `ORDER_COMPLETED`, `ORDER_CANCELLED_BY_CUSTOMER`, `ORDER_CANCELLED_BY_ADMIN`, `ORDER_EXPIRED`, `ORDER_INVENTORY_RELEASED`.

Domain event interface:

```java
public interface OrderEventPublisher {
  void orderCreated(Order order);
  void orderAccepted(Order order);
  void orderRejected(Order order, String reason);
  void orderReady(Order order);
  void orderCompleted(Order order);
  void orderCancelled(Order order, String reason, Actor actor);
  void orderExpired(Order order);
  void menuDateCancelled(MenuDate date, String reason);
}
```

S5 ships a no-op/local logger implementation. S6 implements notification handling.

## API operations

- `createOrder`
- `listCustomerOrders`
- `getCustomerOrder`
- `cancelCustomerOrder`
- `listAdminOrders`
- `getAdminOrder`
- `acceptOrder`
- `rejectOrder`
- `markOrderReady`
- `markOrderCompleted`
- `cancelAdminOrder`
- `getBakeList`
- `getAdminSummary`

Payment endpoint and notification operations are S6.

## Ordered tasks

1. Add `V5__orders.sql`, entities, repositories, and order number sequence.
2. Implement customer guard and request validation.
3. Implement option validation and total calculation.
4. Implement atomic window and offering reservation with deadlock-safe ordering.
5. Implement order/line/option inserts and `OrderCreated` event.
6. Implement customer order reads and cancel.
7. Implement admin accept/reject/ready/complete/cancel.
8. Implement expiry scheduler and idempotent release.
9. Implement menu/date cancellation propagation.
10. Implement bake list aggregation.
11. Add audit and event publisher.
12. Add concurrency and lifecycle tests.
13. Seed orders for W5/W6/W7 and A6.

## Acceptance criteria

- Given one unit remaining, when two customers submit concurrently, then exactly one succeeds and the other gets 409 `SOLD_OUT`.
- Given the same customer retries the same checkout with the same `idempotency_key`, then the server returns the existing order and no duplicate order/counter changes occur.
- Given two concurrent orders for the same customer where total exceeds `maxPerCustomer`, then exactly one succeeds and the other gets 409 `CUSTOMER_LIMIT_EXCEEDED`.
- Given a window with one order remaining, when two orders submit concurrently, then exactly one succeeds with 409 `WINDOW_FULL` for the other.
- Given quantity 2 of a $4 item, then total is 800 cents.
- Given a +$1 option and quantity 2, then line total is 1000 cents.
- Given required group with no selection, then 400 `INVALID_CUSTOMIZATION`.
- Given an option from another offering, then 400 `INVALID_CUSTOMIZATION`.
- Given an order after cutoff, then 409 `CUTOFF_PASSED`.
- Given `maxPerCustomer=3` and two existing active orders totaling 2, when ordering 2 more, then 409 `CUSTOMER_LIMIT_EXCEEDED`.
- Given a valid order, then counters increase by quantity/one, order number matches `BB-YYYYMMDD-0001`, status `PENDING`, payment `UNPAID`.
- Given customer order filters, then `ACTIVE` excludes terminals, `COMPLETED` shows only completed, and `CANCELLED` groups rejected/cancelled/expired.
- Given an unaccepted order at cutoff, when the scheduler runs, then it becomes `EXPIRED` and counters are released once.
- Given a rejected order, then counters release once and a second reject fails.
- Given a customer cancel before bake day, then `CANCELLED` and release.
- Given a customer cancel on bake day, then 409 `CANCELLATION_CLOSED`.
- Given a cancelled date, then all active orders on it are cancelled and released.
- Given `ACCEPTED`, `READY`, and `COMPLETED` orders, when bake list requested, then totals include them and exclude pending/cancelled.
- Given a bake date, then the admin summary counts pending/accepted/ready/completed and unpaid active orders.
- Given a quantity limit reduced below reserved, then existing orders remain and new orders fail `SOLD_OUT`.

## Test plan

- Unit: totals, option rules, state transitions, time rules including DST, idempotency key handling.
- Integration: create order happy path and every validation failure.
- Integration: duplicate submit with same `idempotency_key` returns same order, no counter change, no duplicate outbox/event.
- Concurrency: parallel reservations for one offering and one window with a thread pool and separate transactions.
- Concurrency: parallel orders for same customer hitting `maxPerCustomer` — exactly one succeeds.
- Integration: expiry scheduler with a fixed clock, release idempotency.
- Integration: accept/reject/ready/complete/cancel permissions and states.
- Integration: per-customer limit across multiple orders.
- Integration: menu/date cancellation propagation.
- Integration: bake list aggregation including option counts.
- Integration: customer can only read own orders.
- Performance smoke: 50 concurrent order attempts complete under a few seconds.

## Example payloads for W5/W6/W7 and A6 fixtures

W5/W6/W7 and A6 build against MSW fixtures; they do not load a seeded server. Use these representative responses as the seeded order shapes in `web/test/` and Android debug fixtures. All IDs below are UUID v7 examples; timestamps are UTC.

### `POST /api/v1/orders` — `201 Created` (new) or `200 OK` (idempotent replay)

```json
{
  "id": "01a1f188-5368-71b6-8350-d06fc674b9f2",
  "orderNumber": "BB-20261208-0001",
  "status": "PENDING",
  "paymentStatus": "UNPAID",
  "paymentMethod": null,
  "menuDateId": "01a1f188-504e-7469-b8f9-fc5c70fcbddd",
  "bakeDate": "2026-12-08",
  "pickupWindow": {
    "id": "01a1f188-50a2-7b4c-8e0f-1d2c3b4a5967",
    "label": "Morning",
    "startAt": "2026-12-08T19:00:00Z",
    "endAt": "2026-12-08T21:00:00Z"
  },
  "customer": { "name": "Buyer One", "email": "buyer@example.com", "phone": null },
  "lines": [
    {
      "id": "01a1f188-53a1-7b6e-8350-d06fc674b9f3",
      "offeringId": "01a1f188-51ce-729f-b78d-5f4ba0d8c21f",
      "itemName": "Cinnamon Roll",
      "unitPriceCents": 400,
      "quantity": 2,
      "lineTotalCents": 1000,
      "options": [{ "groupName": "Toppings", "optionName": "Icing", "priceDeltaCents": 100 }],
      "notes": null
    }
  ],
  "subtotalCents": 1000,
  "totalCents": 1000,
  "notes": null,
  "rejectionReason": null,
  "cancellationReason": null,
  "cancelledBy": null,
  "acceptedAt": null,
  "readyAt": null,
  "completedAt": null,
  "paidAt": null,
  "delayNotifiedAt": null,
  "delayReason": null,
  "expiresAt": "2026-12-08T07:00:00Z",
  "createdAt": "2026-11-18T18:00:00Z",
  "updatedAt": "2026-11-18T18:00:00Z",
  "paymentInstructions": {
    "venmoHandle": "bethanysbakery",
    "venmoDisplayName": "Bethany's Bakery",
    "amountCents": 1000,
    "note": "BB-20261208-0001",
    "venmoDeepLink": null
  }
}
```

Status variants for W6/W7 history fixtures change only `status` and the matching timestamp/reason fields: `ACCEPTED` sets `acceptedAt`, `READY` sets `readyAt`, `COMPLETED` sets `completedAt`, `CANCELLED` sets `cancelledBy` (`CUSTOMER`, `ADMIN`, or `SYSTEM`) plus `cancellationReason`, `REJECTED` sets `rejectionReason`, `EXPIRED` sets `cancelledBy: "SYSTEM"` and `cancellationReason: "Cutoff passed"`.

### `GET /api/v1/me/orders` and `GET /admin/v1/orders` — `200 OK`

```json
{
  "items": [
    { "id": "01a1f188-5368-71b6-8350-d06fc674b9f2", "orderNumber": "BB-20261208-0001", "status": "READY", "totalCents": 1000 }
  ],
  "total": 1
}
```

`items` entries are full `Order` objects; truncated here for brevity. Customer `filter` values: `ALL`, `ACTIVE` (pending/accepted/ready), `COMPLETED`, `CANCELLED` (rejected/cancelled/expired).

### `GET /admin/v1/bake-list?date=2026-12-08` — `200 OK`

```json
{
  "bakeDate": "2026-12-08",
  "generatedAt": "2026-11-18T18:05:00Z",
  "orderCount": 2,
  "items": [
    {
      "offeringId": "01a1f188-51ce-729f-b78d-5f4ba0d8c21f",
      "itemName": "Cinnamon Roll",
      "totalQuantity": 5,
      "options": [{ "groupName": "Toppings", "optionName": "Icing", "count": 5 }]
    }
  ]
}
```

### `GET /admin/v1/summary?date=2026-12-08` — `200 OK`

```json
{
  "bakeDate": "2026-12-08",
  "pending": 1,
  "accepted": 1,
  "ready": 2,
  "completed": 0,
  "unpaid": 3
}
```

## Security checklist

- [x] Customer can only read/cancel own orders — `findByIdAndCustomerId` on both endpoints; cross-customer read and cancel return 404.
- [x] CSRF double-submit on every customer order mutation — `RequestAuthenticationFilter.requiresCsrf()` covers `/api/v1/orders` and `/api/v1/orders/**`; 403 `CSRF_INVALID` without token.
- [x] Admin endpoints require mTLS + device key + bearer — `SecurityConfig` `/admin/v1/**` chain; existing S2 device filter.
- [x] No client-supplied totals or prices are trusted — prices/deltas read from offering snapshots in `OrderPricing`; request DTO has no amount fields.
- [x] Option IDs validated against the offering snapshot — `OrderPricing.price` checks membership, availability, duplicates, and required/min/max per group.
- [x] Concurrency counters cannot go negative — conditional reservation updates plus `GREATEST(0, ...)` in `InventoryService.release`.
- [x] Expiry/release idempotent — conditional status updates and `inventory_released_at IS NULL` claim; verified by repeated job runs.
- [x] Audit all mutations — `ORDER_*` events plus `ORDER_INVENTORY_RELEASED` on every transition.
- [x] PII limited to order owner/admin — customer order reads scoped by customer ID; order-list endpoints under `/admin/v1` gated.
- [x] Rate limit order creation — `RateLimiter` 20/minute per customer on `POST /api/v1/orders` (429 `RATE_LIMITED`).
- [x] Rate limit order cancellation — `RateLimiter` 20/minute per customer on `POST /api/v1/orders/{id}/cancel`.
- [x] Free-text reasons not logged — `LoggingOrderEventPublisher` logs order IDs/status only; reason stays in the event for S6.

## Quality gate

This phase is not complete until `/phase-gate S5` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

### S5 gate results

| Agent | Status | Findings | Action |
|---|---|---|---|
| `spec-compliance` | PASS (rerun) | B1 duplicate option IDs; B2 `required` with `min=0`; W3–W7 | Fixed: original option list reaches pricing, duplicate rejection before merge, `required` check, page size 20, checklist signed, per-customer row lock, injected `Clock`, test gaps |
| `lint` | PASS | No lint tooling configured; style warnings on fully-qualified types | Style imports applied via refactor; tooling gap routed to S7 |
| `refactor` | PASS | FQ types, duplicated quantity aggregation, long `create` method | Imports added; `requireOrderableCustomer` extracted; `quantitiesByOffering` shared; phase suite green |
| `test-quality` | PASS | Vacuous/weak assertions; 12 unproven acceptance criteria | Tests strengthened; 8 named tests added; all 20 criteria provable; full suite 181 tests green |
| `security` | PASS (rerun) | Blocker: CSRF missing on order create/cancel; 7 warnings | CSRF added + 403 `CSRF_INVALID` test; cancel rate limit, no reason in logs, `optionIds @Size`, cross-customer cancel test |
| `gap-analysis` | PASS | V6 duplicate `outbox_event`, missing error codes, W5 blind spot, deferrals | S5/S6/S7/W5 specs and domain model updated; see Accepted gate findings below |

## Definition of done

All acceptance criteria pass, migration applies, concurrency tests prove no oversell, and W5/W6/A6 can build against the order contract.

## Handoff notes

- S6 implements payment endpoint, notification handling for the event interface, and Venmo instructions from env.
- S5's local event publisher logs only; no email/SMS/push.
- S7 load-tests order creation and reviews the reservation SQL.

### Accepted gate findings (non-blocking; owner noted where work remains)

- **Error codes** — order creation returns 409 `MENU_NOT_AVAILABLE` (validation step 1) and 409 `WINDOW_NOT_AVAILABLE` (step 2), plus `OFFERING_NOT_AVAILABLE` (step 4). These codes are recorded in `docs/api/openapi.yaml` and the `docs/specs/README.md` problem-code table. W5 checkout fixtures must handle them (routed to W5).
- **Audit action label (W8)** — menu/date cancellation is system-driven but audits as `ORDER_CANCELLED_BY_ADMIN` with `ActorType.SYSTEM`; no distinct `ORDER_CANCELLED_BY_SYSTEM` code exists. Accepted for S5; S7 owns the decision to add a distinct audit code and update the code registry/consumers.
- **Scheduler teardown noise (W9)** — `@EnableScheduling` (added for `OrderExpiryJob`) leaves scheduled tasks active in test contexts, producing connection-refused log lines during Testcontainers teardown. Non-failing; S6 owns profile/property gating for all schedulers (including the S5 expiry job).
- **Operation IDs (W1)** — the OpenAPI document defines `operationId` for all S5 operations (73 across the document); the warning concerns generated clients not surfacing them. Accepted baseline across phases; S7 OpenAPI validation should assert presence/uniqueness.
- **Security deferrals** — in-memory `RateLimiter` (single-instance assumption), full stack traces logged by `ApiExceptionHandler` on 500, and `AuditService` `SENSITIVE` regex not covering free-text `reason` are routed to S7 hardening.
- **Test-quality gaps** — 50-concurrent smoke, fixed-`Clock` expiry, whole-menu cancellation, concurrent same-key idempotency, line-merge rules, additional validation paths, bake-list `REJECTED`/`EXPIRED` exclusion, and outbox `available_at`/exact key are routed to S7 verification.
