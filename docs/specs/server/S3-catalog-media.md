# S3 — Catalog, Ingredients, Customizations, and Media

- Status: ready
- Depends on: S2
- Contract: `docs/api/openapi.yaml` → Admin Catalog, Public media
- Next: S4

## Goal

Let the admin manage reusable item templates with photos, ingredient text, allergen tags, and customization groups. Provide safe image upload, resizing, and public image serving.

## Non-goals

- Menus and offerings (S4).
- Order snapshots (S4/S5).
- Customer menu UI (W3/W4).

## Data model — `V3__catalog.sql`

Create exactly as described in `docs/domain-model.md`:

- `media`
- `item_template`
- `customization_group`
- `customization_option`

Constraints:

- Unique media storage key and SHA-256 where appropriate.
- `customization_group`: `min_selections >= 0`, `max_selections >= 1`, `min <= max`, `SINGLE` requires `max_selections = 1`.
- `customization_option.price_delta_cents >= 0`.
- Cascade delete groups/options when a template is replaced? Do not delete templates. Full replacement may recreate nested rows.
- Foreign keys with sensible `ON DELETE` behavior. Media referenced by a template cannot be deleted.

## Item template rules

- Name required, 1–120 chars.
- Description optional, max 2000 chars.
- Ingredients free text, max 4000 chars.
- Allergen tags: array of short strings, max 20, each max 60 chars, deduplicated case-insensitively.
- Active templates can be offered; inactive templates cannot be added to new offerings.
- Customization groups:
  - Ordered by `sort_order`.
  - `SINGLE`: exactly one selection if required; zero or one if optional.
  - `MULTI`: between `min_selections` and `max_selections`.
  - At least one option per group.
  - Option names unique within a group.
  - Price delta is non-negative cents.
  - Unavailable options remain visible to admin but are hidden or disabled for customers.
- `PUT` replaces the full template, including groups/options. Nested IDs are optional; omitted IDs create new rows; removed groups/options are deleted only if not referenced by an offering snapshot (S4 snapshots, so template deletes are safe).
- Deactivate instead of hard delete. `DELETE` sets `active=false`.

## Media rules

- Accept `multipart/form-data` field `file`.
- Max 10 MB.
- Allowed content: JPEG, PNG, WebP. Reject SVG, GIF, PDF, and unknown bytes.
- Validate by magic bytes, not just `Content-Type`.
- Re-encode with Java ImageIO/Thumbnailator:
  - Strip EXIF and metadata.
  - Correct orientation.
  - Max long edge 1600 px.
  - Generate WebP variants at 400, 800, 1600 px where applicable.
  - Store original normalized WebP plus variants.
- Store under `/data/media/{yyyy}/{mm}/{uuid}/...`; never use user filenames as paths.
- Compute SHA-256 of the normalized original.
- Persist width, height, size, variants, content type.
- Public URL: `GET /api/v1/media/{mediaId}?size=400|800|1600`, cacheable, immutable ETag.
- Delete only if no template references it in this phase. Otherwise 409 `MEDIA_IN_USE`.
- S4 extends the same delete check to offering photo references.
- Upload and delete write audit events.

Implementation dependencies (justify in the PR):

- `webp-imageio` (or an equivalent ImageIO WebP reader/writer) is required because the JDK has no WebP codec.
- `metadata-extractor` (or `commons-imaging`) for EXIF orientation and metadata stripping.
- `Thumbnailator` for high-quality resizing.
- Reject images whose declared dimensions exceed a safe decode bound before full decode.

## API operations

- `listItemTemplates`
- `createItemTemplate`
- `getItemTemplate`
- `replaceItemTemplate`
- `deactivateItemTemplate`
- `uploadMedia`
- `deleteMedia`
- `getMedia`

## Ordered tasks

1. Add `V3__catalog.sql` and entities/repositories.
2. Implement template validation and full-replacement service.
3. Implement item template endpoints.
4. Add media storage service with path traversal protection.
5. Implement image validation, EXIF stripping, resizing, WebP variants.
6. Implement media upload/delete/get endpoints and cache headers.
7. Add reference checks for media deletion.
8. Add audit events.
9. Add tests and local media volume in compose.
10. Update env/README for `MEDIA_ROOT` and upload limits.

## Acceptance criteria

- Given a valid template payload, when created, then it is returned with sorted customization groups and options.
- Given `SINGLE` with `maxSelections > 1`, when saved, then 400 `VALIDATION_ERROR`.
- Given `MULTI` with `min > max`, when saved, then 400 `VALIDATION_ERROR`.
- Given a duplicate option name in one group, when saved, then 400 `VALIDATION_ERROR`.
- Given a 12 MB file, when uploaded, then 413 with a problem response.
- Given a file named `x.jpg` containing SVG, when uploaded, then 400 `UNSUPPORTED_MEDIA_TYPE`.
- Given a valid photo, when uploaded, then original dimensions are capped at 1600 px, EXIF is absent, and 400/800/1600 variants exist.
- Given a photo referenced by a template, when deleted, then 409 `MEDIA_IN_USE`.
- Given a photo referenced only by an offering, when deleted after S4, then 409 `MEDIA_IN_USE`.
- Given an unused photo, when deleted, then the row is marked deleted and files are removed.
- Given an inactive template, when listed with `active=true`, then it is excluded.
- Given a deactivated template, when adding to an offering in S4, then the server rejects it.

## Test plan

- Repository/service unit tests for customization rules.
- Integration: template create/replace/get/list/deactivate.
- Integration: duplicate names, min/max, single selection.
- Integration: media upload valid JPEG/PNG/WebP, invalid magic bytes, oversized file.
- Integration: resize dimensions, variants, EXIF stripping.
- Integration: media in use conflict, delete unused.
- Security: path traversal filename, decompression bomb guard, content-type spoof.
- Cache: ETag/Cache-Control present.

## Security checklist

- [ ] Magic-byte validation and re-encoding.
- [ ] No SVG or script-bearing formats.
- [ ] Random storage paths; no user filename paths.
- [ ] 10 MB limit enforced at server and proxy.
- [ ] Image dimensions capped before decode where possible.
- [ ] Media delete requires admin auth.
- [ ] No EXIF/GPS leakage.
- [ ] Audit upload/delete.

## Quality gate

This phase is not complete until `/phase-gate S3` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

All acceptance criteria pass, media is served with cache headers, migrations apply, and A4/W4 can use the template/media contract.

## Handoff notes

- S4 copies template fields and customization groups into offering snapshots.
- Template edits after S4 must not mutate published offerings.
- S7 adds nginx/Cloudflare cache and upload limit settings.
