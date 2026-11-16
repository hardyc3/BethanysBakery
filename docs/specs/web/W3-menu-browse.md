# W3 — Menu Browse, Dates, and Availability

- Status: ready
- Depends on: W2, S3, S4
- Contract: `docs/api/openapi.yaml` → Public menu read
- Design: `design examples/design_exmaples.json` → **Customer · Weekly menu**
- Next: W4

## Goal

Show published bake dates/windows, offerings, prices, photos, and live availability so customers can browse and start building a basket from the weekly menu.

## Non-goals

- Full item customization/detail beyond what the menu card supports (W4).
- Checkout submission and final validation (W5).
- Order status/updates (W6/W7).

## Screens

### Weekly menu home

- Route: `/` (customer **Menu** tab).
- Top bar/brand:
  - bakery logo, "Bethany’s", "Gluten Free Bakery", tagline "SMALL BATCH · HOMEMADE".
- Hero/intro:
  - eyebrow: "FROM MY KITCHEN TO YOUR TABLE".
  - heading: "A little homemade\nhappiness."
  - subheading: "This week’s bakes · <date range>" (e.g. `November 16–22`) derived from the current published menu/week.
- Baking day / pickup selector:
  - horizontal day chips/cards per bake day (e.g. "Fri, Nov 20" with "Pickup 4–6 pm", "Sun, Nov 22" with "Pickup 10 am–12 pm").
  - defaults to earliest available bake day.
  - selected day drives menu list.
  - URL state: `?date=<menuDateId>` for shareability; include window context if multiple windows exist.
  - if a bake day has multiple pickup windows/times, expose window choice clearly while preserving compact chip/card feel.
  - full/closed windows/days disabled with clear labels.
- Order cutoff notice:
  - prominent notice near selector (e.g. "Preorder by Thursday at 6 pm") from server-provided cutoff.
- Menu section heading:
  - contextual heading (e.g. "Made for Friday") + item count (e.g. "3 bakes").
- Offering cards/list:
  - photo thumbnail (lazy, aspect ratio reserved, responsive variants/srcset when available).
  - name and short descriptor (e.g. "Slow-fermented · 1 loaf").
  - price.
  - availability pill: "<X> left" or "Sold out".
  - inline quantity stepper for available items (e.g. `−  1  +`) to add/update basket quantity; bounded by availability/per-customer/99.
  - sold-out items: show "Sold out" and "Back another week" (no stepper/add).
  - tap name/photo opens item detail/customization (W4) when item has options or more info; ingredient list must remain reachable from detail (`#ingredients`).
- Sticky bottom basket bar (when cart has items):
  - "View basket  ·  <n> items  ·  $<total>" → cart/checkout (W5).
- Bottom navigation:
  - **Menu**, **My orders**, **Updates**.
- Empty/loading/error states:
  - no published menu/dates (friendly branded empty state).
  - date with no offerings, all sold out, ordering closed.
- Refresh:
  - refetch on focus; poll every 45s while visible; manual refresh.
  - keep previous data during refetch (no flicker).

### Availability copy/states

- `availableQuantity > 0`: "<X> left".
- `availableQuantity == 0`: "Sold out" + "Back another week"; ordering disabled.
- window/day full: disabled/"Full".
- cutoff passed: disabled/"Ordering closed" with cutoff notice.

## Data and state

- Query keys: `['menu-dates', from, to]`, `['menu-date', menuDateId]`.
- `staleTime`: 15s; `refetchOnWindowFocus: true`.
- No caching availability across sessions.
- Selected date/window in URL.
- Cart store shared with W4/W5 (session-scoped, `bb_cart_v1` or agreed version): scoped to one `menuDateId` and one `pickupWindowId`; changing date/window requires confirm/clear.
- Use server-provided `orderCutoffAt`.

## API integration

- `listPublicMenuDates`
- `getPublicMenuDate`
- `getPublicOffering` (detail/customization in W4).

## Components

- `MenuHeader`/brand block
- `DatePickupSelector`
- `OrderCutoffNotice`
- `OfferingCard` (photo, descriptor, price, availability, stepper/sold-out)
- `AvailabilityBadge`
- `BasketBar`
- `CustomerBottomNav`
- `MenuEmptyState`, `MenuLoadingSkeleton`, `RefreshButton`

## Ordered tasks

1. Add public menu API functions/types and cart-store contract (shared with W4/W5).
2. Implement brand header/hero and menu-week context.
3. Implement date/pickup selector and URL state.
4. Implement menu section heading, item count, and offering cards.
5. Add inline quantity stepper, sold-out/back-next-week states, and cart updates.
6. Add sticky basket bar and bottom navigation.
7. Add cutoff notice, full/closed/empty states.
8. Add polling/focus refetch/manual refresh and image handling.
9. Add responsive layout and accessibility.
10. Add tests (MSW) and Playwright smoke.
11. Document query keys, cart/date-window scoping, and cache behavior.

## Acceptance criteria

- Given published dates, when page loads, then earliest available day is selected and offerings render with week/date context.
- Given day chips, then selected day shows pickup time/context and menu heading/count for that day.
- Given an available item, then quantity stepper updates cart and basket bar shows items/total.
- Given "View basket", then user navigates to cart/checkout.
- Given a sold-out item, then "Sold out" and "Back another week" show and no quantity can be added.
- Given cutoff passed/full window, then ordering controls disabled with clear notice.
- Given no published menu, then friendly empty state appears.
- Given refetch with changed availability, then UI updates without flicker/jump.
- Given nav tabs, then Menu/My orders/Updates routes are reachable.
- Given 360px width, then layout stacks, text readable, basket bar/nav usable.

## Test plan

- Component/query: loading, success, empty, error.
- Selector/date URL state and invalid fallback.
- Stepper bounds, cart totals, cross-date/window confirm behavior.
- Availability/sold-out/full/closed states and cutoff display.
- Polling/focus refetch with fake timers; image fallback.
- Playwright: browse → select day → adjust qty → view basket.
- Accessibility: chip/stepper labels, headings, nav, contrast/focus.

## Security checklist

- [ ] Only published data rendered.
- [ ] No raw HTML from descriptions.
- [ ] Image URLs restricted to allowed/API origins.
- [ ] No PII in URLs.
- [ ] Client prices/totals display-only; server authoritative at checkout.

## Quality gate

This phase is not complete until `/phase-gate W3` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Menu browse matches the weekly-menu design: branded hero, day/pickup selection, cutoff, item cards with availability/stepper, basket bar, and bottom nav, with live availability and safe cart scoping.

## Handoff notes

- W4 handles item customization/detail and cart-line merge rules for options/notes.
- W5 revalidates cart and submits order; availability may change between browse and checkout.
- Design shows inline quantity on menu; if an item requires customizations, stepper/add should route into W4 configuration flow before adding.
