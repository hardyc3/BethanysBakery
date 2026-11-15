# W3 — Menu Browse, Dates, and Availability

- Status: ready
- Depends on: W2, S3, S4
- Contract: `docs/api/openapi.yaml` → Public menu read
- Next: W4

## Goal

Show published bake dates, pickup windows, offerings, prices, photos, and live availability so customers can decide what to order.

## Non-goals

- Item detail and customizations (W4).
- Cart/checkout (W5).
- Order status (W6).

## Screens

### Menu home

- Route: `/`.
- Heading with bakery name and current menu context.
- Date selector:
  - horizontal chips on mobile, list/segmented control on larger screens.
  - shows weekday, date, and sold-out/closed state.
  - defaults to the earliest available date.
  - URL state: `?date=<menuDateId>` for shareability.
- Pickup window selector:
  - appears after date selection.
  - shows label, time range, capacity remaining.
  - full windows disabled with "Full".
  - cutoff-passed windows disabled with "Ordering closed".
- Offering grid:
  - photo from `photoUrl`, with `srcset`/`sizes` built from `photoVariants` keys `400`, `800`, `1600`; lazy loaded, aspect ratio reserved.
  - name, short description
  - price
  - availability: "12 left" / "Sold out"
  - allergen chips
  - "View" link to W4 (item detail).
  - "Ingredients" link to W4 ingredients section (`#ingredients`) so customers can view the ingredient list directly from the menu card.
- Sold-out offerings remain visible with a clear sold-out notice/badge; detail view is allowed but ordering actions are disabled in W4.
- Empty states:
  - no published dates
  - date with no offerings
  - all offerings sold out
  - ordering closed
- Refresh:
  - refetch on focus.
  - poll every 45 seconds while the menu page is visible.
  - manual refresh control.
  - do not flicker sold-out state; keep previous data during refetch.

### Availability copy

- `availableQuantity > 0`: "X left" (hide exact count if > 9? Keep exact; scale is small.)
- `availableQuantity == 0`: "Sold out".
- window `availableCapacity == 0`: "Full".
- cutoff passed: "Ordering closed".

## Data and state

- Query keys:
  - `['menu-dates', from, to]`
  - `['menu-date', menuDateId]`
- `staleTime`: 15 seconds.
- `refetchOnWindowFocus: true`.
- No caching of availability across sessions.
- Selected date/window persisted in URL only, not localStorage.
- Use server-provided `orderCutoffAt`; do not recompute cutoff client-side except for display countdown.

## API integration

- `listPublicMenuDates`
- `getPublicMenuDate`
- `getPublicOffering` for detail links (W4 owns the detail screen).

## Components

- `DateSelector`
- `PickupWindowSelector`
- `OfferingCard`
- `AvailabilityBadge`
- `AllergenChips`
- `MenuEmptyState`
- `MenuLoadingSkeleton`
- `RefreshButton`

## Ordered tasks

1. Add public menu API functions and types.
2. Implement date list query and URL state.
3. Implement date detail query and window selector.
4. Build offering grid/cards and availability badges.
5. Add sold-out, full, closed, and empty states.
6. Add polling, focus refetch, and manual refresh.
7. Add responsive layout and image handling.
8. Add tests with MSW.
9. Add Playwright menu smoke.
10. Document query keys and cache behavior.

## Acceptance criteria

- Given published dates, when the page loads, then the earliest date is selected and offerings appear.
- Given no dates, then a friendly "No menus yet" state appears.
- Given a date URL parameter, then that date is selected on load and invalid IDs fall back safely.
- Given an offering with zero availability, then it shows a clear "Sold out" notice/badge (customer notified in-app) and ordering is unavailable; the item detail may still be opened to view ingredients/allergens.
- Given an offering card, then an "Ingredients" link navigates to the item's ingredient list on the detail view.
- Given a full window, then it is disabled and labeled "Full".
- Given cutoff passed, then the window shows "Ordering closed".
- Given a refetch returns fewer items, then availability updates without layout jump.
- Given an image fails, then a placeholder is shown.
- Given 360 px width, then cards stack and text does not overflow.
- Given keyboard navigation, then date/window controls and offering/ingredient links are usable.

## Test plan

- Component/query tests: loading, success, empty, error.
- Availability badge states.
- URL state and invalid date fallback.
- Polling/focus refetch behavior with fake timers.
- Image error fallback.
- Playwright: menu loads, date switch, sold-out disabled.
- Accessibility: image alt text, button labels, headings.

## Security checklist

- [ ] Only published data is rendered.
- [ ] No raw HTML from descriptions.
- [ ] External image URLs allowed only from the API base.
- [ ] Links use safe navigation.
- [ ] No PII in URLs.

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

The menu page is usable on mobile, availability is clear and live, empty/error states are handled, and W4 can link to item detail.

## Handoff notes

- W4 uses `offeringId` from the card and the selected `menuDateId`/`pickupWindowId`.
- Availability may change between browse and checkout; W5 revalidates on submit.
- If many dates are added later, add pagination/date range limits.
