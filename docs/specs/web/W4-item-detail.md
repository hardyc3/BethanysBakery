# W4 — Item Detail, Ingredients, and Customizations

- Status: ready
- Depends on: W3, S4
- Contract: `docs/api/openapi.yaml` → `getPublicOffering`
- Design: `design examples/design_exmaples.json` → **Customer · Weekly menu** (item cards, ingredients/detail entry)
- Next: W5

## Goal

Let customers inspect an item, read ingredients/allergens, choose valid customizations and quantity, see price updates, and add/update the configured line in the basket.

## Non-goals

- Checkout submission and pickup confirmation (W5).
- Payment/status (W6).
- Admin editing (A4/A5).

## Screen

Route: `/menu/:menuDateId/items/:offeringId?window=<pickupWindowId>` (or modal/sheet from menu, same content contract).

Sections:

1. Photo (hero, responsive variants).
2. Name, short descriptor, price.
3. Availability: "<X> left" / "Sold out" with clear in-app notice.
4. Ingredients (`id="ingredients"`):
   - reachable from menu/detail; expanded when opened via `#ingredients`.
   - free-text list, preserve line breaks.
5. Allergens: prominent chips/tags and warning text when present.
6. Customization groups (if any):
   - in `sortOrder`; single-select radios, multi-select checkboxes with min/max.
   - required marker; unavailable options disabled/labeled.
   - price delta per option (`+$0.50`, `Included`).
   - group-level validation.
7. Quantity stepper (bounded by availability, per-customer limit, and 99).
8. Notes (optional, max 300 chars).
9. Sticky footer/action area:
   - computed line/unit total when relevant.
   - primary action: Add to basket / Update basket (or equivalent) and/or "View basket".
   - disabled when sold out, cutoff/closed, or invalid.
10. Sold-out: item info/ingredients visible, ordering disabled, show "Sold out"/"Back another week" messaging consistent with W3.

## Menu integration

- Simple items without required customizations may be added/adjusted via inline stepper on W3 menu cards.
- Items requiring customization/notes must route to this detail flow before add.
- Basket bar ("View basket · n items · $total") remains visible/consistent with W3.

## Cart state

- Shared cart store (provider/reducer) with W3/W5.
- Persist to `sessionStorage` under `bb_cart_v1` (bump version if shape changes).
- Scoped to one `menuDateId` and one `pickupWindowId`.
- Different date/window: confirm "Start a new cart?" then clear and add.
- Cart line identity: `offeringId` + sorted `optionIds` + line notes.
- Same identity increments quantity (max 99 / limits).
- Store display snapshots: offeringId, itemName, descriptor, unit price, options (names/deltas), quantity, notes, photo URL, availability at add time.
- Server authoritative; W5 revalidates.

## Validation

- SINGLE required: exactly one; SINGLE optional: zero or one.
- MULTI: between min/max.
- Unique option IDs; quantity >= 1; notes length.
- Do not trust client prices at checkout.

## Price computation

- `unitPrice = offering.priceCents + sum(selected option deltas)`.
- `lineTotal = unitPrice * quantity`.
- Shared money helper (USD); show breakdown when deltas exist.

## API integration

- `getPublicOffering`.
- Use selected `menuDateId`/`pickupWindowId` from W3.
- 409/404/unavailable: explain and link back to menu.
- Refetch on focus; disable add if unavailable.
- `photoVariants` is a sparse map: the server only emits sizes generated for that image, so `400`/`800`/`1600` keys may be missing for small photos. Build `srcset` from the keys that are present (fall back to `photoUrl`); MSW fixtures must include a partial-variants case (carried over from S4's gate findings).
- Customization group/option IDs are regenerated on every offering update (including snapshot-preserving updates). Do not persist/cached-ID assumptions for `customizationGroups[].id`/`options[].id` across an update; refetch before submitting `optionIds` with a cart/order (server validates against the current snapshot).

## Ordered tasks

1. Add offering detail query/types and shared cart contract usage.
2. Build detail layout, ingredients/allergen sections, and `#ingredients` behavior.
3. Build customization controls and validation.
4. Implement price breakdown, quantity, notes.
5. Implement add/update/cart merge and cross-date/window confirmation.
6. Integrate with W3 inline-add/basket bar behavior.
7. Add sold-out/closed states.
8. Add tests for option rules, cart identity, and persistence.
9. Add Playwright add/customize smoke.

## Acceptance criteria

- Given item with required customization, menu add routes to detail and Add blocked until valid.
- Given valid multi-select min/max, selection constraints enforced.
- Given option price delta and quantity, line total correct.
- Given unavailable option, disabled.
- Given sold-out item, info/ingredients visible but add disabled with "Back another week"/sold-out notice.
- Given `#ingredients` navigation, ingredients visible/focused.
- Given same configuration added twice, quantities merge; different config creates separate line.
- Given different date/window, confirmation clears cart.
- Given refresh, cart restored from sessionStorage.
- Given allergens, visible without needing to hunt.

## Test plan

- Unit: validation, price calc, cart identity/merge/persistence.
- Component: option groups, sold-out/cutoff, notes, ingredients anchor.
- Integration with W3 basket/stepper behavior.
- Playwright: menu/detail → customize → add/update → basket.
- Accessibility: labels, group semantics, errors announced, focus.

## Security checklist

- [ ] No raw HTML for descriptions/ingredients.
- [ ] Option IDs only from API response.
- [ ] Prices display-only; checkout server-authoritative.
- [ ] Notes escaped; cart storage has no PII/tokens.

## Quality gate

This phase is not complete until `/phase-gate W4` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Customers can configure items and manage basket lines with correct pricing/validation; W3 inline add and W5 checkout share a consistent cart contract.

## Handoff notes

- W5 must revalidate all lines and handle sold-out/cutoff/limit/customization errors.
- Keep cart schema versioned (`bb_cart_v1`).
- Do not store money as floats.
