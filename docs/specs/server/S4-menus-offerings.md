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
  - Snapshot customization group and option IDs are regenerated on every update, including the `null`-keep path. `sourceGroupId`/`sourceOptionId` retain the original snapshot lineage internally but are not exposed in the API. Clients (W4/A5) must not cache `customizationGroups[].id` or `options[].id` across an offering update; re-read the offering before submitting option IDs with an order (S5 validates option IDs against the current snapshot).
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

- `GET /api/v1/menu-dates` returns only dates belonging to `PUBLISHED` menus and with date status `PUBLISHED`; `from` defaults to today in `America/Denver`, and `to` defaults to `from + 60 days` (the range is anchored on the resolved `from`, so if the caller supplies `from` and omits `to`, the upper bound is 60 days after that supplied `from`, not 60 days after today). Each summary includes `windowCount`, `offeringCount`, and the earliest window `orderCutoffAt`.
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

## Example payloads for W3/W4 fixtures

W3/W4 build against MSW fixtures (per `W1-scaffold.md`); they do not load a seeded server. The following are representative responses from the three public endpoints. Copy these shapes into `web/test/` fixtures. All three responses carry `Cache-Control: private, no-store, max-age=0`. `photoUrl`/`photoVariants` use `APP_PUBLIC_BASE_URL` (example: `https://public.example.com`).

`photoVariants` contains only the sizes that were actually generated for that media: `ImageProcessor` skips any requested variant larger than the source image's long edge, so a small photo can legitimately omit `800` and/or `1600`. (A photo with no media returns `photoUrl: null` and an empty `photoVariants`.) Clients must pick the largest available key rather than assuming all three keys exist; W3/W4 fixtures should include a partial-variants case to exercise `srcset` fallback (see the deferred-gaps note below).

`GET /api/v1/menu-dates?from=...&to=...` — `PublicMenuDateSummary[]`:

```json
[
  {
    "id": "01a1ec77-e13a-7e66-9e12-493cac21e62a",
    "bakeDate": "2026-12-17",
    "status": "PUBLISHED",
    "windowCount": 2,
    "offeringCount": 3,
    "orderCutoffAt": "2026-12-17T16:00:00Z"
  }
]
```

`GET /api/v1/menu-dates/{menuDateId}` — `PublicMenuDate` (note the sold-out offering with `availableQuantity: 0`, the zero-capacity window, and absolute photo URLs):

```json
{
  "id": "01a1ec77-e13a-7e66-9e12-493cac21e62a",
  "menuId": "01a1ec77-e128-7fd7-be26-bb67be484ff6",
  "title": "Weekly Menu",
  "notes": "Pickup at the bakery.",
  "bakeDate": "2026-12-17",
  "status": "PUBLISHED",
  "pickupWindows": [
    {
      "id": "01a1ec77-e140-1111-1111-111111111111",
      "label": "Morning",
      "startAt": "2026-12-17T19:00:00Z",
      "endAt": "2026-12-17T21:00:00Z",
      "availableCapacity": 12,
      "orderCutoffAt": "2026-12-17T07:00:00Z"
    },
    {
      "id": "01a1ec77-e140-2222-2222-222222222222",
      "label": "Afternoon (full)",
      "startAt": "2026-12-17T22:00:00Z",
      "endAt": "2026-12-18T00:00:00Z",
      "availableCapacity": 0,
      "orderCutoffAt": "2026-12-17T10:00:00Z"
    }
  ],
  "offerings": [
    {
      "id": "01a1ec77-e167-7b91-a71c-cbd70d065433",
      "name": "Cinnamon Roll",
      "description": "Fresh-baked, with optional toppings.",
      "photoUrl": "https://public.example.com/api/v1/media/01a1ec77-e150-3333-3333-333333333333",
      "photoVariants": {
        "400": "https://public.example.com/api/v1/media/01a1ec77-e150-3333-3333-333333333333?size=400",
        "800": "https://public.example.com/api/v1/media/01a1ec77-e150-3333-3333-333333333333?size=800",
        "1600": "https://public.example.com/api/v1/media/01a1ec77-e150-3333-3333-333333333333?size=1600"
      },
      "priceCents": 550,
      "availableQuantity": 8,
      "soldOut": false,
      "ingredientsText": "Flour, butter, cinnamon, sugar, yeast.",
      "allergenTags": ["Gluten", "Dairy"],
      "windowIds": ["01a1ec77-e140-1111-1111-111111111111"],
      "customizationGroups": [
        {
          "id": "01a1ec77-e170-4444-4444-444444444444",
          "name": "Toppings",
          "selectionType": "SINGLE",
          "required": true,
          "minSelections": 1,
          "maxSelections": 1,
          "options": [
            { "id": "01a1ec77-e175-5555-5555-555555555555", "name": "Icing", "priceDeltaCents": 50, "available": true },
            { "id": "01a1ec77-e175-6666-6666-666666666666", "name": "Nuts", "priceDeltaCents": 75, "available": false }
          ]
        }
      ]
    },
    {
      "id": "01a1ec77-e167-7777-7777-777777777777",
      "name": "Sold-out Loaf",
      "description": "Back another week.",
      "photoUrl": null,
      "photoVariants": {},
      "priceCents": 700,
      "availableQuantity": 0,
      "soldOut": true,
      "ingredientsText": null,
      "allergenTags": [],
      "windowIds": ["01a1ec77-e140-2222-2222-222222222222"],
      "customizationGroups": []
    }
  ]
}
```

`GET /api/v1/offerings/{offeringId}` returns a single `PublicOffering` with the same shape as a `PublicMenuDate.offerings[]` element above. Server tests: `MenuIntegrationTest` (`server/src/test/java/com/bethanysbakery/api/menu/MenuIntegrationTest.java`) is the authoritative source for field names and headers.

## Security checklist

- [x] All admin endpoints require mTLS + device key + bearer. Enforced globally by the admin security filter chain; integration tests authenticate with bearer + device key.
- [x] Public endpoints expose only published data. `PublicMenuService` queries `findPublicById`/`findPublicBetween` (menu + date `PUBLISHED`) and filters cancelled windows/offerings; cancelled resources return 404.
- [x] No reserved counts changed by this phase. S4 only reads `reserved_orders`/`reserved_count`; S5 owns reservation counters.
- [x] Audit events for publish/cancel/edits. `MENU_CREATED/UPDATED/DELETED/PUBLISHED/CANCELLED`, `MENU_DATE_*`, `PICKUP_WINDOW_*`, `OFFERING_*` recorded via `AuditService`.
- [x] Quantity/price validation prevents negative or zero values. DB checks + DTO/service validation (`priceCents 0..99999`, `quantityLimit 1..9999`, `maxOrders > 0`, `maxPerCustomer >= 1`).
- [x] No cross-date window assignment. `OfferingService.validateWindows` requires every window to be active and belong to the same bake date; publish validator re-checks assignments. Public availability also uses `Cache-Control: private, no-store, max-age=0`.

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

## Quality gate results

Final results of `/phase-gate S4` (all six gates ran). Gate order matches `docs/specs/README.md` §4.

| agent | status | findings | action taken |
|---|---|---|---|
| spec-compliance | PASS | Initial FAIL: snapshot-blocker (publish could leak template data into existing offering snapshots). Final rerun clean; 149 tests green. | Fixed snapshot-blocker; reran and confirmed PASS. |
| lint | PASS | No lint/secret findings in scope; no formatter configured (tracked by S7 developer-tooling gap). Final rerun `./gradlew cleanTest check --max-workers=1` green: 149 tests, 0 fail/skip/error; parallel-only `Could not write XML test results` was a WSL `/mnt/e` mount flake, not a source regression. | None required; static-analysis tooling deferred to S7. |
| refactor | PASS | 5 small readability/maintainability edits, no behavior change; suite green. | Applied safe edits; reran suite green. |
| test-quality | PASS | Final verdict PASS (initial FAIL: 2 NPE bugs found and fixed). 30 S4 tests green. | Fixed both NPE bugs; confirmed 30/30 S4 tests green. Remaining coverage gaps recorded as non-blocking deferred items below. |
| security | PASS | Final verdict PASS; no blockers. W1/W2/W6 resolved in phase; W3/W4/W5/W7 deferred (owners below). | Fixed W1/W2/W6; routed W3/W4/W5/W7 to owning specs (see Deferred gaps). |
| gap-analysis | PASS | Spec-vs-implementation review complete. Findings: one ambiguous rule (`to` default), one undocumented client-facing behavior (regenerated snapshot IDs), one fixture blind spot (partial `photoVariants`), plus deferred items already owned by later specs. | Clarified `to` default and snapshot-ID regeneration in this spec; documented partial `photoVariants` and fixture guidance; verified and recorded all remaining deferred items below. No source changes needed. |

### Deferred gaps

Every item below is recorded with an owner/spec. None is a blocking S4 defect.

| Gap | Severity | Evidence | Owner / action |
|---|---|---|---|
| No true concurrent-race test for `MediaReferenceService.lockAndCheckActive` (deliberately avoided a flaky test; single-threaded `offeringPhotoReferenceBlocksMediaDeletion` covers the `MEDIA_IN_USE` path). | Medium | `MediaReferenceService.java:41-46`; `MenuIntegrationTest.offeringPhotoReferenceBlocksMediaDeletion`. | **S7** (`S7-hardening-deploy.md` "Media hardening ownership"): already recorded there that the lock mechanism is done in S4 and the true race test remains a gap. No S4 action. |
| No Checkstyle/Spotless/PMD/OpenAPI-validator (or yamllint/hadolint/JaCoCo) configured. | Low | `server/build.gradle.kts` has no such tasks; S4 lint gate relied on manual checks. | **S7** (`S7-hardening-deploy.md` "Developer tooling", lines ~314-318): already owned. No S4 action. |
| `source_group_id` / `source_option_id` are plain nullable UUIDs, not FKs. | Info (not a gap) | `V4__menus.sql`; `docs/domain-model.md:298,310` both describe them as nullable, no FK. | None — intentional snapshot lineage, domain model matches. |
| Customization group/option IDs are regenerated on every offering update (including the `null`-keep path); originals kept only as internal `sourceGroupId`/`sourceOptionId`. | Medium | `OfferingService.copyCurrentGroups/snapshotRequestGroups` generate `Uuids.v7()` for groups and options. | **S4 spec** (documented in "Offering rules" above) + **W4/A5 clients**: must re-read the offering and not cache group/option IDs across updates; S5 validates submitted option IDs against the current snapshot. |
| Public `photoVariants` may omit `400`/`800`/`1600` keys for small images; the S4 fixture example always shows all three. | Medium | `MenuViews.publicOffering` emits only `media.getVariants().keySet()`; `ImageProcessor` skips sizes larger than the long edge (`if (size > longEdge) continue`). | **S4 spec** (documented in fixture section above) + **W3/W4** (`W3-menu-browse.md`, `W4-item-detail.md`): fixtures must include a partial-variants case and clients must select the largest available key. W3 already states "responsive variants/srcset when available"; W4 hero/srcset should be checked. |
| Publish-validator group/option checks (duplicate names, `SINGLE` max=1, options 1-50) are not directly unit-tested; guarded by create-time validation + DB checks. | Low | `MenuPublishValidator.validateGroups`; covered indirectly, no dedicated publish test. | **S4 test backlog** (non-blocking): add a publish-validation test with corrupted snapshot groups if this path is exercised by future work. |
| Offering update with an explicit `customizationGroups` array replacement is not directly tested (the `null`-keep path is covered). | Low | `MenuIntegrationTest.offeringUpdateWithNullSnapshotFieldsNeverPullsFromEditedTemplate` omits the field; helper `offeringBody` never sets `customizationGroups`. | **S4 test backlog** (non-blocking): add one update test that sends a replacement array. |
| `maxPerCustomer` upper bound on **update** is not directly tested (create-time rejection is tested). | Low | `MenuIntegrationTest.maxPerCustomerAboveQuantityIsRejected` exercises create only. | **S4 test backlog** (non-blocking). |
| Cascade menu/date cancellation writes no per-child audit rows; only `MENU_CANCELLED`/`MENU_DATE_CANCELLED`. | Info (intentional) | S4 spec line 75 requires only those two codes; child rows transition without their own audit events. | None — intentional per spec; S5/S6 may add order-level audit/events. |
| mTLS leg not S4-test-covered (S2-owned; `ADMIN_MTLS_LOCAL_BYPASS` profile-gated in tests). | Low | `SecurityConfig` / `application-test.yml` bypass; gate uses bearer + device key. | **S2 / S7**: S7 acceptance criteria cover real mTLS verification. No S4 action. |
| `APP_PUBLIC_BASE_URL` defaults to empty, so public media URLs are relative if a prod environment omits it. | Medium | `application.yml:67` → `app-public-base-url: ${APP_PUBLIC_BASE_URL:}`. | **S7** (`S7-hardening-deploy.md` app checks, lines ~54-56 reject non-HTTPS public/admin URLs): ensure `check-env.sh`/startup rejects an empty value. No S4 code change. |
| `DB_PASSWORD` fallback default (`bakery`) in `application.yml` (pre-existing, out of S4 scope). | Medium | `application.yml:9` → `password: ${DB_PASSWORD:bakery}`. | **S7** (`S7-hardening-deploy.md` app checks, lines ~54-56 explicitly reject local/dev DB credential defaults). No S4 action. |
| `ItemTemplateServiceTest` has an unused `@Mock MediaRepository` leftover (harmless; no failing/skipped test). | Info | `ItemTemplateServiceTest.java:40` (`@Mock mediaRepository`) unused after refactor. | **S4 test backlog** (non-blocking cleanup): remove the unused mock/import in a later tidy-up. |
