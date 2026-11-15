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

1. Menu date exists, belongs to a published menu, and is `PUBLISHED`.
2. Window exists, is `ACTIVE`, belongs to the date.
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

## Security checklist

- [ ] Customer can only read/cancel own orders.
- [ ] Admin endpoints require mTLS + device key + bearer.
- [ ] No client-supplied totals or prices are trusted.
- [ ] Option IDs validated against the offering snapshot.
- [ ] Concurrency counters cannot go negative.
- [ ] Expiry/release idempotent.
- [ ] Audit all mutations.
- [ ] PII limited to order owner/admin.
- [ ] Rate limit order creation.

## Quality gate

This phase is not complete until `/phase-gate S5` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

All acceptance criteria pass, migration applies, concurrency tests prove no oversell, and W5/W6/A6 can build against the order contract.

## Handoff notes

- S6 implements payment endpoint, notification handling for the event interface, and Venmo instructions from env.
- S5's local event publisher logs only; no email/SMS/push.
- S7 load-tests order creation and reviews the reservation SQL.
