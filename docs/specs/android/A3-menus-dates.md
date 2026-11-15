# A3 — Menu and Bake Date Management

- Status: ready
- Depends on: A2, S4
- Contract: `docs/api/openapi.yaml` → Admin Menus
- Next: A4

## Goal

Let Bethany create menus, add bake dates, publish with notification settings, edit published menus, and cancel menus or dates with a reason.

## Non-goals

- Item templates (A4).
- Offerings and pickup windows (A5).
- Push notification delivery (A7/A8).

## Screens

### Menu list

- Tabs/filters: Draft, Published, Cancelled, All.
- Rows:
  - title
  - status badge
  - first bake date
  - date count
  - updated time
- FAB: New menu.
- Pull-to-refresh.

### Menu editor

- Title (required, 120 chars).
- Notes (2000 chars).
- Dates section:
  - list with bake date, status, window/offering counts.
  - add date via date picker.
  - draft dates can be deleted.
  - published dates can be cancelled with reason.
  - tap date → A5 (offerings/windows).
- Save draft.
- Publish action:
  - validation summary from server.
  - notification toggle default ON.
  - notification date/time: leave the field at its server-derived default; the app sends `notifyAt=null` (or omits it) so the server computes 4 days before the first bake date at 09:00 Denver. If the admin edits it, send the chosen instant.
  - confirm dialog with summary.
- Cancel menu:
  - destructive confirmation.
  - optional reason (max 500).
  - explains active orders will be cancelled and refunds are manual.
- Published menu:
  - title/notes editable.
  - add date (publishes immediately).
  - no delete, only cancel.
- Cancelled:
  - read-only, reason shown.

### Date cancel

- Reason optional.
- Warning: active orders are cancelled and customers notified.
- Manual refund note.

## Rules

- Bake date must be today or later in America/Denver.
- Publishing requires at least one date, one window, one offering per date, and offering/window assignments. Show field-level server errors.
- If the admin does not edit the notification time, send `notifyAt=null`/omit it and display the server-computed value from the response. If edited, send the chosen instant. Do not compute the default on-device.
- Notification can be disabled.
- Adding a date to a published menu is immediate and sends no new menu announcement.
- Destructive actions require explicit confirmation.
- All times shown in America/Denver; the device timezone may differ. Label the timezone.

## API integration

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

## Ordered tasks

1. Menu list query and filters.
2. Create/edit draft form with validation.
3. Date picker and draft date CRUD.
4. Publish flow with notification toggle/date and validation errors.
5. Cancel menu/date dialogs and reason.
6. Published-menu edit rules.
7. Loading/empty/error/retry states.
8. Tests with MockWebServer.
9. Compose UI tests for publish/cancel.
10. Seed/mock data for local previews.

## Acceptance criteria

- Given no menus, then an empty state with "New menu" appears.
- Given a draft with title and date, then it saves and appears in Draft.
- Given a draft missing windows/offerings, when publishing, then server validation errors are shown per field/date.
- Given notification enabled, then the publish request includes it and the default time from server is displayed.
- Given notification disabled, then the request disables it.
- Given a published menu, then title/notes can be edited and dates can be added.
- Given a published date, then delete is unavailable and cancel is available.
- Given cancel with a reason, then the status updates and reason is shown.
- Given a cancelled menu, then editing is blocked.
- Given a device timezone other than Denver, then dates/times display with the correct Denver semantics.
- Given a network failure, then the form state is preserved and retry is offered.

## Test plan

- MockWebServer: CRUD, publish validation errors, cancel.
- Date math around DST.
- Compose UI: create/publish/cancel flows, confirmation dialogs.
- Offline/error state.
- Accessibility: labels, destructive confirmation, status text.

## Security checklist

- [ ] Admin auth on every request.
- [ ] Destructive actions confirmed.
- [ ] Reasons escaped.
- [ ] No PII in logs.
- [ ] No local optimistic status changes before server confirmation.
- [ ] Timezone explicit.

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

Bethany can create, publish, edit, and cancel menus/dates from the app with clear warnings and errors.

## Handoff notes

- A5 handles windows/offerings required for publish.
- A7 handles notification queue/reminders; A3 only sets publish notification settings.
- Menu cancellation order propagation is server-side (S5).
