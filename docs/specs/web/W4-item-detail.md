# W4 — Item Detail, Ingredients, and Customizations

- Status: ready
- Depends on: W3, S4
- Contract: `docs/api/openapi.yaml` → `getPublicOffering`
- Next: W5

## Goal

Let customers inspect an item, read ingredients/allergens, choose valid customizations and quantity, see the price update live, and add the configured line to the cart.

## Non-goals

- Checkout and pickup window confirmation (W5).
- Payment (W6).
- Admin editing (A4/A5).

## Screen

Route: `/menu/:menuDateId/items/:offeringId?window=<pickupWindowId>`.

Sections:

1. Photo (hero, responsive variants).
2. Name, description, price.
3. Availability ("X left" / "Sold out") — clear in-app sold-out notice when unavailable.
4. Ingredients (`id="ingredients"`):
   - accessible via direct link/anchor from menu cards.
   - collapsed disclosure by default, expanded when opened via `#ingredients`.
   - free-text list, preserve line breaks.
5. Allergens:
   - prominent chips.
   - optional warning text if tags exist.
6. Customization groups:
   - rendered in `sortOrder`.
   - single-select uses radios.
   - multi-select uses checkboxes with min/max.
   - required marker.
   - unavailable options disabled and labeled.
   - price delta shown per option (`+$0.50`, `Included`).
   - group-level validation messages.
7. Quantity stepper (1–99, bounded by per-customer/availability where known).
8. Notes field (optional, max 300 chars) for the line.
9. Sticky footer:
   - computed unit price
   - line total
   - Add to cart
   - disabled when sold out/cutoff/closed or invalid.
10. Sold-out state:
    - show item info but disable add.
    - explain that availability changed.

## Cart state

Introduce a cart store in this phase.

- `CartProvider` with reducer.
- Persist to `sessionStorage` under `bb_cart_v1`.
- Cart is scoped to one `menuDateId` and one `pickupWindowId`.
- If an item is added from a different date/window:
  - show a confirm dialog: "Start a new cart?"
  - on confirm, clear and add.
- Cart line identity: `offeringId` + sorted `optionIds` + line notes.
- If the same identity is added, increment quantity up to 99.
- Store snapshots for display:
  - offeringId, itemName, unit price, selected options (group/option names, deltas), quantity, notes, photo URL, available quantity at add time.
- Server remains authoritative; W5 revalidates.

## Validation

- SINGLE required: exactly one.
- SINGLE optional: zero or one.
- MULTI: between min and max.
- Option IDs unique.
- Quantity >= 1.
- Notes length.
- Do not trust client prices at checkout; use server totals.

## Price computation

- `unitPrice = offering.priceCents + sum(selected option deltas)`.
- `lineTotal = unitPrice * quantity`.
- Display with the shared money helper.
- Show a breakdown when deltas exist.

## API integration

- `getPublicOffering`.
- Use menu date/window context from W3.
- If offering no longer available, show 409/404 state and link back to menu.
- Refetch offering on focus; if availability drops to 0, disable add.

## Ordered tasks

1. Add offering detail query and types.
2. Build detail layout and disclosures.
3. Build customization controls with validation.
4. Implement price breakdown.
5. Implement quantity and notes.
6. Add cart provider/reducer and sessionStorage persistence.
7. Implement add-to-cart and cross-date/window confirmation.
8. Add sold-out/closed states.
9. Add tests for option rules and cart identity.
10. Add Playwright add-to-cart smoke.

## Acceptance criteria

- Given an item with a required single-select group, when no option is chosen, then Add is disabled and an error is shown.
- Given a multi-select group with min 1/max 2, when 3 options are chosen, then the third is prevented or an error is shown.
- Given a +$1 option and quantity 2, then the line total shows `(item + 1) * 2`.
- Given an unavailable option, then it is disabled and cannot be selected.
- Given a sold-out item, then Add is disabled and the sold-out state is clearly explained (in-app notification, no separate message required).
- Given navigation to `#ingredients`, then the ingredient list section is focused/expanded and readable.
- Given the same configuration is added twice, then one cart line has quantity 2.
- Given a different option set, then a separate cart line is created.
- Given a cart from date A and adding from date B, then a confirmation clears the old cart.
- Given a page refresh, then the cart is restored from sessionStorage.
- Given allergens, then they are visible without expanding ingredients.
- Given 360 px width, then the sticky footer does not cover content.

## Test plan

- Unit: option validation, price calculation, cart identity/merge.
- Component: single/multi groups, min/max, required, disabled options.
- Component: sold-out and cutoff states.
- Cart persistence and cross-date confirmation.
- Playwright: item detail → customize → add → cart indicator.
- Accessibility: radiogroup/checkbox group labels, errors announced.

## Security checklist

- [ ] No raw HTML for descriptions/ingredients.
- [ ] Option IDs come only from the API response.
- [ ] Prices are display-only; checkout uses server totals.
- [ ] Notes are escaped.
- [ ] Cart storage contains no PII/tokens.

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

Customers can configure items with valid customizations, see accurate prices, and add them to a persistent cart. W5 can read the cart contract.

## Handoff notes

- W5 must revalidate all cart lines against the server and handle sold-out/cutoff/limit errors.
- Cart schema version `bb_cart_v1`; bump if the shape changes.
- Do not store prices as floats.
