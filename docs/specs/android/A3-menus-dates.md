# A3 — Menus, Bake Dates, and Weekly Menu Editor

- Status: ready
- Depends on: A2, S4
- Contract: `docs/api/openapi.yaml` → Admin Menus, Admin Notifications (recipient preview)
- Design: `design examples/design_exmaples.json` → **Bethany · Bakery overview** (menu card/actions), **Bethany · Edit weekly menu**
- Next: A4

## Goal

Let Bethany view/manage menus, edit the weekly menu (days, items summary, pickup/cutoff settings), publish with notifications, and cancel menus or dates.

## Non-goals

- Full item template authoring (A4).
- Offering price/quantity/window assignment details beyond editor needs (A5).
- Push delivery implementation (A7/A8).

## Screens

### Baker overview (home/dashboard context)

- Route/tab: **Overview** (bottom nav: Overview / Menus / Orders).
- Header: brand/logo, "Bethany’s", "Gluten Free Bakery", "BAKER’S VIEW"; greeting "Good morning, Bethany." and "A good week is rising." (time/context-aware copy acceptable).
- Published weekly menu card:
  - week label (e.g. "NOVEMBER 16–22"), **Live** badge when published.
  - "This week’s menu" title, bake days summary (e.g. "Friday, Nov 20  ·  Sunday, Nov 22").
  - actions: "Edit menu  →" (opens editor) and "Share link  ↗" (public menu link/copy/share as supported).
- Snapshot and bake/pickup summary cards may appear (see A6 for operational details) plus "+ Create next week’s menu".

### Menu list (Menus tab)

- Filters/tabs as needed (Draft/Published/Cancelled/All).
- Rows: title/week, status, dates, updated time.
- Create new menu; pull-to-refresh.

### Weekly menu editor

- Header: "This week’s menu" with status badge (e.g. **Live**); subtitle "November 16–22 · Changes save as a draft." (or accurate save state).
- Baking days selector:
  - day chips/tabs (e.g. "Fri, Nov 20", "Sun, Nov 22") and "+ Add a baking day".
  - select day to edit its items and pickup settings.
- Menu items section (per selected day as applicable):
  - item editor cards (e.g. sourdough): photo thumbnail, "Replace photo  ↗" / upload (JPG/PNG), name, description, price per item, bake limit for day.
  - reserved/remaining indicators (e.g. "18 ordered", "6 still available") and auto-sold-out note (e.g. "When all 24 are ordered, this item is automatically marked sold out for Friday.").
  - manage additional items (e.g. "2 more items · Manage →") linking to full item/offering management (A4/A5).
- Pickup & order cutoff (per day as applicable):
  - "Pickup & order cutoff" section.
  - fields: Pickup starts, Pickup ends, Slot length, Orders per slot, Orders close (datetime, e.g. "Thu, Nov 19  ·  6:00 pm").
  - pickup address display/context (e.g. "Pickup address · 124 Maple Lane").
  - times/slots must map to server pickup-window/capacity/cutoff model (`America/Denver`); align/update API/domain if slot-level fields are introduced.
- Publish/notification:
  - "Keep your customers in the loop" with copy like "Publishing sends a new-menu alert to subscribers."
  - notification enable/date defaults per server rules; show computed/default time when applicable.
  - Recipient preview from `previewNotificationRecipients`: count chip ("X customers will be notified") with per-channel breakdown; "View recipients" opens A7's shared preview panel with masked destinations. Show as-of time and note that the count can change before a scheduled send.
  - **Publish changes** action.
- Cancel menu/date flows with confirmation and optional reason (destructive).

### Bottom navigation

- **Overview**, **Menus**, **Orders**.

## Rules

- Bake dates/times interpreted in `America/Denver`; show timezone context where needed.
- Draft changes saved explicitly/clearly; published/live menu edits follow server constraints.
- Publish requires server-valid dates/windows/offerings/assignments; show validation errors.
- Adding/publishing/cancelling follow domain state machines and audit/notification rules.
- Slot length/orders-per-slot UI must produce valid server pickup windows/capacities (or be backed by equivalent API fields).

## API integration

- `listMenus`, `createMenu`, `getMenu`, `updateMenu`, `deleteDraftMenu`, `publishMenu`, `cancelMenu`
- `addMenuDate`, `getMenuDate`, `updateMenuDate`, `deleteDraftMenuDate`, `cancelMenuDate`
- `previewNotificationRecipients` for `MENU_PUBLISHED` and `MENU_CANCELLED`.
- Related offering/window/media endpoints as needed from A4/A5.

## Ordered tasks

1. Overview/menu entry points and Menus tab list.
2. Create/edit menu shell and draft save behavior.
3. Baking day chips/add/remove/cancel per rules.
4. Weekly editor item summary/photo/fields with reserved/remaining display.
5. Pickup & order cutoff/slot settings mapped to server model.
6. Publish flow with notification messaging, recipient preview count/detail, and validation errors.
7. Cancel flows and confirmations.
8. Bottom nav and navigation to A4/A5/A6 screens.
9. Tests (MockWebServer, Compose UI) and fixtures.

## Acceptance criteria

- Given published menu, overview shows week, Live badge, bake days, Edit menu and Share link actions.
- Given editor, baking days selectable and addable; selected day shows relevant items and pickup/cutoff settings.
- Given item fields/limits, ordered vs available counts and sold-out behavior displayed accurately.
- Given pickup starts/ends, slot length/orders per slot, and orders close, values persist and map to valid server windows/cutoff.
- Given Publish changes, notification intent and current recipient count are shown, "View recipients" lists masked recipients from the server preview, and server validation/errors are handled.
- Given cancel actions, confirmations/reasons/status updates correct.
- Given draft/live states, save/publish/edit rules enforced.

## Test plan

- Unit: date/timezone mapping, slot/window derivation, validation.
- MockWebServer: menu/date CRUD, publish/cancel, validation errors.
- Compose UI: overview, editor navigation, day switching, publish/cancel dialogs, recipient count chip and preview panel.
- Accessibility: labels, controls, status badges, confirmations.

## Security checklist

- [ ] Admin auth on requests.
- [ ] Destructive actions confirmed.
- [ ] No PII/secrets in logs.
- [ ] Server source of truth for counts/status; no unsafe optimistic mutations.

## Quality gate

This phase is not complete until `/phase-gate A3` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Overview and weekly menu editor match design and allow Bethany to manage menu days, key item/pickup settings, and publish/cancel flows safely.

## Handoff notes

- A4/A5 own deep template/customization and per-offering/window assignment editing; keep "Manage" links and data consistent.
- If slot length/orders-per-slot requires model/API changes, update `docs/domain-model.md` and `docs/api/openapi.yaml` with this phase/change set.
- Share-link behavior depends on public web route availability.
- Recipient preview UI comes from A7's shared panel; A3 only supplies event type, entity type, and entity ID.
