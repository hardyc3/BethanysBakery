# A5 — Offerings, Prices, Quantities, and Pickup Windows

- Status: ready
- Depends on: A4, S4
- Contract: `docs/api/openapi.yaml` → Admin Menus (offerings, pickup windows)
- Next: A6

## Goal

Let Bethany configure each bake date: pickup windows with order caps, and offerings with price, bake quantity, per-customer limits, window assignment, and customization overrides.

## Non-goals

- Item template editing (A4).
- Order management (A6).
- Customer UI (W3–W5).

## Screens

### Bake date detail

- Header: bake date, menu status, reminder sent state.
- Sections: Pickup windows, Offerings.
- Actions: add window, add offering, preview availability.
- Publish prerequisite warnings if menu is draft.

### Pickup windows

- List: label, start–end, max orders, reserved/available.
- Create/edit:
  - label (60)
  - start/end date-time on the bake date, America/Denver
  - max orders (≥1)
- Time picker uses Denver timezone regardless of device timezone; show timezone label.
- Reducing max below reserved is allowed with a warning:
  - existing orders honored
  - no new orders until below limit
- Delete only when no reserved orders and no offering assignment; otherwise explain.

### Offerings

- List: photo, name, price, available/limit, reserved, max per customer, window count, status.
- Add offering:
  - select an active item template (searchable).
  - snapshot preview of name/description/photo/ingredients/allergens/customizations.
  - optional overrides for name, description, photo, ingredients, allergens.
  - price in dollars (store cents).
  - quantity limit (≥1).
  - max per customer (optional).
  - assign one or more pickup windows.
  - customization override editor (same rules as A4) or "use template snapshot".
- Edit:
  - price, quantity limit, max per customer, windows, snapshot text/photo, customization snapshot.
  - template selection is immutable; create a new offering to change templates.
  - lowering quantity below reserved allowed with warning.
- Cancel offering:
  - allowed only when no active order lines; explain.
- Availability preview: `max(0, quantityLimit - reservedCount)`.

## Validation

- Price: non-negative, two-decimal input, max reasonable (e.g. $999.99).
- Quantity: 1–9999.
- Max per customer: optional, 1–quantityLimit.
- At least one window.
- Windows must belong to the same bake date.
- Offering snapshot customizations valid.
- Item template must be active.
- Duplicate template per date is rejected by unique constraint; show a clear error.

## API integration

- `listPickupWindows`
- `createPickupWindow`
- `updatePickupWindow`
- `deletePickupWindow`
- `listOfferings`
- `createOffering`
- `getOffering`
- `updateOffering`
- `cancelOffering`
- `getMenuDate`
- `listItemTemplates`

## Ordered tasks

1. Bake date detail shell and queries.
2. Pickup window list/create/edit/delete.
3. Timezone-safe date-time pickers.
4. Offering list and availability display.
5. Add offering with template picker and snapshot preview.
6. Price/quantity/per-customer/window form.
7. Customization override editor.
8. Edit offering and reducing-limit warnings.
9. Cancel offering flow.
10. Tests with MockWebServer and Compose UI.
11. Seed fixtures.

## Acceptance criteria

- Given a date, then windows and offerings load with reserved/available counts.
- Given a valid window, then it saves and displays correct Denver times.
- Given a device in another timezone, then window times still map to Denver.
- Given max orders below reserved, then save succeeds with a warning.
- Given a window with reserved orders, then delete is blocked with an explanation.
- Given an active template, then an offering can be created with a snapshot preview.
- Given an inactive template, then it cannot be selected.
- Given no window selected, then save is blocked.
- Given a window from another date, then save is blocked.
- Given quantity 2 at $4.00, then available shows `2`, price displays `$4.00`.
- Given a quantity reduction below reserved, then save succeeds and availability is 0.
- Given an offering with active orders, then cancel is blocked.
- Given a duplicate template for the date, then a clear error is shown.
- Given a customization override, then it is saved on the offering and does not change the template.

## Test plan

- Unit: money parsing, timezone conversion, availability math, limit validation.
- MockWebServer: window/offering CRUD, validation/conflict errors.
- Compose UI: create/edit offering, template picker, customizations, warnings.
- DST tests for window times.
- Accessibility: time pickers, numeric fields, error text.

## Security checklist

- [ ] Admin auth on all requests.
- [ ] Client sends cents, not floats.
- [ ] No client-supplied reserved counts.
- [ ] Destructive cancel confirmed.
- [ ] Timezone explicit.
- [ ] No PII.

## Quality gate

This phase is not complete until `/phase-gate A5` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Bethany can fully configure a bake date's windows and offerings, including quantities and customizations, with safe warnings for live changes.

## Handoff notes

- A6 uses offering/order data for the bake list.
- Publish validation happens in A3 but depends on this screen being complete.
- Existing orders keep price/option snapshots after offering edits.
