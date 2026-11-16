# A4 — Item Templates, Photos, Ingredients, and Customizations

- Status: ready
- Depends on: A3, S3
- Contract: `docs/api/openapi.yaml` → Admin Catalog
- Design: `design examples/design_exmaples.json` → **Bethany · Edit weekly menu** (item photo/name/description fields and "Replace photo" affordance in editor context)
- Next: A5

## Goal

Let Bethany create reusable item templates with photos, ingredient lists, allergen tags, and customization groups/options used when building menus.

## Non-goals

- Offering prices/quantities/windows (A5).
- Customer menu UI (W3/W4).

## Screens

### Template list

- Search by name.
- Filter active/inactive.
- Rows: photo thumb, name, allergen count, customization group count, active badge.
- FAB: New item.

### Template editor

- Photo:
  - pick from gallery or camera.
  - preview and replace.
  - compress/resize client-side to max 1600 px and under 10 MB before upload.
  - upload progress/error/retry.
- Name (required, 120).
- Description (2000).
- Ingredients text (4000), multiline.
- Allergen tags:
  - chip input with suggestions (milk, eggs, wheat, soy, peanuts, tree nuts, fish, shellfish, sesame).
  - free-form custom tags.
  - dedupe case-insensitively; max 20.
- Active toggle.
- Customization groups:
  - add/remove/reorder.
  - group name, selection type (single/multi), required, min/max, sort order.
  - options: name, price delta, available, reorder.
  - validation mirroring S3:
    - single → max 1
    - min ≤ max
    - at least one option
    - unique option names in group
    - non-negative deltas.
- Save:
  - full replacement.
  - show server field errors.
- Deactivate:
  - confirmation; explains existing offerings keep their snapshot.
- Unsaved-changes guard on back.

## Rules

- Photo upload is required before save? No; optional.
- Client-side compression is best-effort; server enforces 10 MB and formats.
- Do not allow SVG/GIF.
- If upload succeeds but save fails, retain the media ID for retry.
- Editing a template does not change published offerings (server snapshots).
- Inactive templates cannot be selected for new offerings (A5).

## API integration

- `listItemTemplates`
- `createItemTemplate`
- `getItemTemplate`
- `replaceItemTemplate`
- `deactivateItemTemplate`
- `uploadMedia`
- `deleteMedia`
- `getMedia`

## Ordered tasks

1. Template list/query/filter.
2. Create/edit form state and validation.
3. Photo picker/camera, compression, upload, preview, retry.
4. Allergen chip editor.
5. Customization group/option editor with reorder and validation.
6. Full-replacement save with error mapping.
7. Deactivate flow.
8. Unsaved-changes guard.
9. Tests with MockWebServer and Compose UI.
10. Local previews/fixtures.

## Acceptance criteria

- Given a valid template, then it saves and appears in the list.
- Given no name, then save is blocked locally.
- Given a single-select group with max > 1, then local validation blocks save.
- Given a multi-select group with min > max, then save is blocked.
- Given duplicate option names in one group, then save is blocked.
- Given a negative price delta, then save is blocked.
- Given a photo, when uploaded, then the compressed image is sent and the returned media ID is attached.
- Given a 12 MB photo, then client compression reduces it or shows an actionable error.
- Given upload failure, then retry works without losing the form.
- Given template edits after an offering exists, then A5 still shows the old offering snapshot.
- Given deactivate, then the template no longer appears for new offerings.
- Given unsaved changes, then back navigation prompts.

## Test plan

- Unit: customization validation, allergen normalization, image size/compression.
- MockWebServer: CRUD, upload error, validation error mapping.
- Compose UI: create/edit, group/option reorder, deactivate confirm, unsaved guard.
- Instrumented: photo picker result and upload.
- Accessibility: form labels, chip removal, reorder controls.

## Security checklist

- [ ] Admin auth on all requests.
- [ ] Upload uses multipart with auth headers.
- [ ] Image type/size checked client-side and server-side.
- [ ] No filenames/paths trusted.
- [ ] No secrets in logs.
- [ ] Destructive deactivate confirmed.
- [ ] Text fields escaped on display.

## Quality gate

This phase is not complete until `/phase-gate A4` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

Bethany can manage reusable items with photos, ingredients, allergens, and valid customizations. A5 can add them to menus.

## Handoff notes

- A5 clones template data into an offering snapshot.
- Template changes do not propagate to published offerings.
- Keep allergen suggestion list configurable in one file.
