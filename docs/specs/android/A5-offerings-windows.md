# A5 — Offerings, Prices, Quantities, and Pickup Windows/Slots

- Status: ready
- Depends on: A4, S4
- Contract: `docs/api/openapi.yaml` → Admin Menus (offerings, pickup windows)
- Design: `design examples/design_exmaples.json` → **Bethany · Edit weekly menu** (item limits, reserved/available, pickup & order cutoff, slot settings)
- Next: A6

## Goal

Let Bethany configure bake-date offerings (price, bake limit, reserved/available) and pickup timing/capacity (windows/slots and order cutoff) in line with the weekly menu editor design.

## Non-goals

- Item template content authoring (A4).
- Order fulfillment actions (A6).

## Screens

### In-editor offering/item settings (per bake day)

- Item card/editor fields:
  - photo, name, description.
  - **Price per item**.
  - **Friday bake limit** (day-specific quantity limit).
  - **18 ordered** / **6 still available** (reserved/remaining) with clear counts.
  - Auto-sold-out note: "When all <limit> are ordered, this item is automatically marked sold out for <day>."
- Manage additional items / navigate to full offering detail as needed.

### Pickup & order cutoff

- Section: "Pickup & order cutoff".
- Fields shown in design: **Pickup starts**, **Pickup ends**, **Slot length**, **Orders per slot**, **Orders close** (date/time).
- Pickup address context shown (read-only display or editable per settings; align with domain/config).
- UI may present slot-level controls; server mapping:
  - must produce valid pickup windows with start/end, `max_orders`/capacity, and cutoff behavior per domain.
  - if server currently models windows (not slots), derive windows/capacities accordingly and/or extend API/domain explicitly (no silent mismatch).

### Offering/window detail (full management)

- Bake date detail: pickup windows and offerings lists with reserved/available, add/edit/delete/cancel rules.
- Offering form: template snapshot/overrides, price (cents), quantity limit, max per customer, window assignment, customization overrides.
- Window form: label/times/capacity; reduce-below-reserved warnings; delete constraints.

## Validation

- Price: non-negative (cents precision), reasonable max.
- Quantity/bake limit: >=1; show reserved/available consistently.
- Max per customer optional and bounded.
- At least one valid pickup window/slot assignment for orderable offerings.
- Pickup/cutoff times valid for bake date in `America/Denver`; orders close before relevant pickup as required.
- Slot length/orders-per-slot must translate to consistent capacities (no overbooking by configuration).
- Snapshot/customization rules per domain.

## API integration

- `listPickupWindows`, `createPickupWindow`, `updatePickupWindow`, `deletePickupWindow`
- `listOfferings`, `createOffering`, `getOffering`, `updateOffering`, `cancelOffering`
- `getMenuDate`, `listItemTemplates`

## Ordered tasks

1. Bake date/offering/window data queries and editor bindings.
2. Item card fields for price, bake limit, ordered/available, sold-out note.
3. Pickup & cutoff UI (starts/ends, slot length, orders per slot, orders close) with timezone-safe pickers.
4. Mapping/validation between slot settings and server windows/capacities/cutoff.
5. Full offering create/edit with template snapshot, overrides, assignments.
6. Window create/edit/delete with warnings/constraints.
7. Error handling, save states, and navigation from A3 editor.
8. Tests (unit mapping/validation, MockWebServer, Compose UI).

## Acceptance criteria

- Given item with bake limit and orders, editor shows ordered and still-available counts and correct sold-out-at-limit messaging.
- Given pickup starts/ends, slot length, orders per slot, and orders close, configuration saves as valid server-recognized windows/capacity/cutoff (or clearly surfaced limitation if contract not yet extended).
- Given device in non-Denver timezone, times reflect `America/Denver` semantics.
- Given capacity/limit reductions below reserved, warnings shown and server rules respected.
- Given offering/window conflicts (duplicate template, invalid assignment, delete blocked), clear errors shown.
- Given A3 editor "Manage" flows, data stays consistent with A4/A5 models.

## Test plan

- Unit: money/quantity math, availability (`limit - reserved`), slot-to-window derivation, timezone/DST.
- MockWebServer: window/offering CRUD, conflicts/validation.
- Compose UI: editor fields, pickers, warnings, save/publish readiness.
- Accessibility: numeric/time inputs, labels, error text.

## Security checklist

- [ ] Admin auth; cents not floats.
- [ ] No client-supplied reserved counts.
- [ ] Destructive actions confirmed.
- [ ] Timezone explicit.

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

Editor and detail screens support bake limits, availability counts, and pickup/cutoff/slot-capacity configuration consistent with design and server invariants.

## Handoff notes

- Slot-level UX in design may require explicit API/domain support; track as contract change if needed.
- A6 depends on accurate reserved/available and window/slot capacities for operations views.
- Existing orders retain snapshots after edits.
