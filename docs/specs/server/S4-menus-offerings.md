# S4 — Menus, Bake Dates, Offerings, and Pickup Windows

- Status: ready
- Depends on: S3
- Contract: `docs/api/openapi.yaml` → Admin Menus, Public menu read
- Next: S5

## Goal

Let the admin build a menu with one or more bake dates, pickup windows, and offerings snapshotted from item templates. Publish menus so customers can read dates, availability, prices, ingredients, allergens, and customizations.

## Non-goals

- Orders and inventory reservation (S5).
- Notification delivery (S6).
- Admin Android UI (A3/A4/A5).
- Customer UI (W3/W4).

## Data model — `V4__menus.sql`

Create exactly as described in `docs/domain-model.md`:

- `menu`
- `menu_date`
- `pickup_window`
- `offering`
- `offering_window`
- `offering_option_group`
- `offering_option`

Constraints/indexes:

- Unique `(menu_id, bake_date)`.
- Unique `(menu_date_id, item_template_id)`.
- `pickup_window.max_orders > 0`, `reserved_orders >= 0`.
- `offering.quantity_limit > 0`, `reserved_count >= 0`, `price_cents >= 0`, `max_per_customer > 0` when present.
- `offering_window` composite PK.
- Check `end_at > start_at`.
- Indexes from `docs/domain-model.md`.

## Menu rules

Statuses: `DRAFT`, `PUBLISHED`, `CANCELLED`.

- Draft menus are editable:
  - title/notes
  - add/update/delete dates
  - add/update/delete windows
  - add/update/delete offerings
- Published menus:
  - Title/notes editable.
  - Add new dates; they publish immediately and do not trigger a new menu notification.
  - Dates cannot be deleted; cancel instead.
  - Windows and offerings editable.
  - Menus cannot be deleted; cancel instead.
- Cancelled menus are read-only.
- Publish validation:
  - At least one non-cancelled date.
  - Every date has at least one active window.
  - Every date has at least one active offering.
  - Every active offering is assigned to at least one active window on the same date.
  - Every assigned window belongs to the offering's date.
  - Every offering's customization snapshot is valid.
  - Bake dates are today or later in `America/Denver`.
- Default notification time:
  - `first_bake_date - 4 days` at `09:00` Denver.
  - If that instant is in the past at publish time, set it to now.
  - Admin may disable or override.
- Publishing writes audit `MENU_PUBLISHED` and sets `published_at`.
- Cancelling a menu/date:
  - Sets `CANCELLED`, `cancelled_at`, optional reason.
  - Cancelling a date also sets all of its pickup windows and offerings to `CANCELLED` so they disappear from public reads.
  - Cancelling a menu applies the same transition to every non-cancelled date, window, and offering it owns.
  - S4 has no orders yet. S5 extends cancellation to cancel active orders and release inventory. Keep the hook/interface in place.
  - Audit `MENU_CANCELLED` / `MENU_DATE_CANCELLED`.

## Bake date rules

- `bakeDate` must be today or later.
- One date per menu.
- Unique per menu.
- Status starts `DRAFT`, becomes `PUBLISHED` with the menu.
- `reminderSentAt` is managed by S6.
- Date cancellation is allowed even after publish. It hides the date and (after S5) cancels active orders.

## Pickup window rules

- Label required, max 60 chars.
- `startAt` and `endAt` must fall on the bake date in `America/Denver`.
- `endAt > startAt`.
- Windows may overlap; the admin owns the schedule.
- `maxOrders` is total orders in that window, not per item.
- `reservedOrders` starts at 0.
- Reducing `maxOrders` below `reservedOrders` is allowed. Existing orders are honored; new reservations fail until the count drops below the limit.
- Windows have no standalone cancel endpoint. When their date is cancelled they become `CANCELLED`.
- Windows with active orders or offering assignments cannot be deleted. Return 409 `WINDOW_IN_USE` and require capacity changes or date cancellation instead.
- Deletion allowed only when `reservedOrders == 0` and no offering is assigned.

## Offering rules

Created from an active item template. Snapshot at creation:

- name, description, photo, ingredients text, allergen tags
- customization groups and options with source IDs
- price is set by the admin, not the template

- `itemTemplateId` is immutable after creation.
- Defaults on create:
  - name/description/photo/ingredients/allergens from template when the request field is `null` or omitted.
  - customization groups from template when `customizationGroups` is `null` or omitted.
- On update:
  - `null` or omitted snapshot fields keep the current offering value.
  - `customizationGroups` `null` keeps the current snapshot; an array replaces it.
- Price per item in cents, `0..99999`.
- `quantityLimit` is total for the bake date, `1..9999`.
- Offerings may reference media; extend the S3 `MEDIA_IN_USE` delete check to offering photos. Use the concurrency-safe reference check defined by S7 ("Media hardening ownership"); the S3 READ_COMMITTED check alone is racy.
- `maxPerCustomer` is optional; null means no per-customer cap. When present it must be `>= 1` and `<= quantityLimit`.
- `windowIds` must be non-empty and belong to the same bake date.
- Offering must be assigned to at least one window before publish.
- Customization validation is the same as templates, including the nested `customizationGroups`/`options` count caps added by S7.
- Offering update:
  - price, quantity limit, max per customer, window assignment, snapshot text/photo, customization snapshot.
  - Reducing `quantityLimit` below `reservedCount` is allowed.
  - Cancelling an offering (`DELETE`) allowed only when no active order lines exist (`PENDING`, `ACCEPTED`, `READY`). S5 enforces authoritative check; S4 may fast-path with `reservedCount == 0` but must not cancel if active order lines exist.
- Availability = `max(0, quantity_limit - reserved_count)`.

## Public read rules

- `GET /api/v1/menu-dates` returns only dates belonging to `PUBLISHED` menus and with date status `PUBLISHED`; `from` defaults to today, `to` defaults to +60 days. Each summary includes `windowCount`, `offeringCount`, and the earliest window `orderCutoffAt`.
- `GET /api/v1/menu-dates/{menuDateId}` returns:
  - active windows with `availableCapacity = max(0, maxOrders - reservedOrders)`
  - active offerings assigned to those windows
  - availability and sold-out flags
  - ingredients, allergens, customization groups/options
  - `orderCutoffAt = startAt - 12h` per window
- Hide cancelled offerings and windows.
- Offering `soldOut` is true when `availableQuantity == 0`.
- `soldOut`/availability fields are the customer-facing sold-out notification signal for UI (in-app). No separate sold-out push/email event is required; clients must display a clear sold-out state.
- A window is effectively unavailable when `availableCapacity == 0` or all its offerings are sold out/cutoff passed; still return it with zero capacity so UI can explain.
- Public media URLs are absolute using `APP_PUBLIC_BASE_URL`.
- Each offering includes `photoUrl` and `photoVariants` (`400`, `800`, `1600`) so clients can build `srcset` without guessing URL parameters.
- Cache headers: short private cache or `no-store` for availability. Do not let Cloudflare cache availability.

## API operations

Admin:

- `listMenus`
- `createMenu`
- `getMenu`
- `updateMenu`
- `deleteDraftMenu`
- `publishMenu`
- `cancelMenu`
- `addMenuDate`
- `getMenuDate`
- `updateMenuDate`
- `deleteDraftMenuDate`
- `cancelMenuDate`
- `listPickupWindows`
- `createPickupWindow`
- `updatePickupWindow`
- `deletePickupWindow`
- `listOfferings`
- `createOffering`
- `getOffering`
- `updateOffering`
- `cancelOffering`

Public:

- `listPublicMenuDates`
- `getPublicMenuDate`
- `getPublicOffering`

## Ordered tasks

1. Add `V4__menus.sql` and entities/repositories.
2. Implement menu/draft CRUD with status guards.
3. Implement date CRUD and validation.
4. Implement pickup window CRUD and capacity edits.
5. Implement offering snapshot creation from templates.
6. Implement offering update and customization snapshot replacement.
7. Implement publish validation and default notification time.
8. Implement cancel menu/date hooks (orders are S5).
9. Implement public menu query and serializers.
10. Add cache headers and absolute media URLs.
11. Add audit events.
12. Add tests and seed data for W3/W4.
13. Extend the S3 media delete reference check to offering photos.

## Acceptance criteria

- Given a draft menu with no dates, when published, then 409 `PUBLISH_VALIDATION_FAILED` with field errors.
- Given a date with no active windows, when published, then publish fails.
- Given an offering not assigned to a window, when published, then publish fails.
- Given a valid menu, when published, then all dates and offerings become publicly visible and `publishedAt` is set.
- Given no notification override, when published, then `notifyAt` is 4 days before the first bake date at 09:00 Denver.
- Given an override in the past, when published, then `notifyAt` is now.
- Given a published menu, when adding a date, then the date is immediately visible and no new menu notification is scheduled.
- Given an offering, when created, then template fields and groups are snapshotted.
- Given a template edited after offering creation, then the offering snapshot does not change.
- Given `maxPerCustomer` greater than `quantityLimit`, then save fails with `VALIDATION_ERROR`.
- Given a quantity limit reduced below reserved count, then availability is 0 and the update succeeds.
- Given a window with reserved orders, when deleted, then 409 `WINDOW_IN_USE`.
- Given a cancelled menu/date, when reading publicly, then 404 or hidden, and its windows/offerings are `CANCELLED`.
- Given a public offering at zero availability, then `soldOut=true`.
- Given a non-US request, S7 enforces geo blocking; S4 does not.

## Test plan

- Unit: publish validation, default notification time across DST, snapshot copy.
- Integration: menu/date/window/offering CRUD, status guards.
- Integration: publish happy/failure, public visibility, cancel.
- Integration: offering snapshot immutability, availability formula.
- Integration: window/offering assignment validation.
- Integration: cache headers and absolute URLs.
- DST tests: March and November transitions in `America/Denver`.

## Security checklist

- [ ] All admin endpoints require mTLS + device key + bearer.
- [ ] Public endpoints expose only published data.
- [ ] No reserved counts changed by this phase.
- [ ] Audit events for publish/cancel/edits.
- [ ] Quantity/price validation prevents negative or zero values.
- [ ] No cross-date window assignment.

## Quality gate

This phase is not complete until `/phase-gate S4` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

All acceptance criteria pass, OpenAPI matches, migrations apply, and A3/A5/W3 can build against the menu contract.

## Handoff notes

- S5 implements order cancellation propagation for menu/date cancellation, and enforces no active order lines on offering cancel.
- S6 sends the menu notification and reminder using `notifyAt` and `reminderSentAt`.
- Availability is read-only here; S5 owns reservation counters.
- S7 owns the media cache-purge-on-delete policy. Offering `photoUrl`/`photoVariants` use the immutable S3 media URLs, so a deleted photo can remain cached until Cloudflare purges it.
