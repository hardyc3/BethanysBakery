# S3 — Catalog, Ingredients, Customizations, and Media

- Status: implemented (follow-ups routed)
- Follow-ups: [S8 review follow-up register](S8-review-followups.md#follow-up-register-all-routed-none-implemented-here)
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

- [x] Magic-byte validation and re-encoding.
- [x] No SVG or script-bearing formats.
- [x] Random storage paths; no user filename paths.
- [ ] 10 MB limit enforced at server and proxy. Server side done (`MEDIA_MAX_UPLOAD_BYTES` + multipart cap); nginx/Cloudflare proxy cap deferred to S7.
- [x] Image dimensions capped before decode where possible.
- [x] Media delete requires admin auth.
- [x] No EXIF/GPS leakage.
- [x] Audit upload/delete.

Deployment note: the runtime image uses glibc (`eclipse-temurin:21-jre`) because the WebP writer (`webp-imageio`) loads a bundled glibc JNI library; Alpine/musl cannot load it. Untrusted WebP input is decoded only with the pure-Java TwelveMonkeys reader, never the bundled native reader.

Cache and privacy note: `GET /api/v1/media/{mediaId}` returns `Cache-Control: public, max-age=31536000, immutable` with an ETag derived from the normalized SHA-256. Deleting media makes the origin return 404, but already-cached copies can stay valid for up to one year. Cloudflare must purge `/api/v1/media/{mediaId}*` when a `MEDIA_DELETED` audit event is written so privacy takedowns are honored. S7 owns the purge procedure or the decision to shorten the TTL.

Rate-limit ownership: S3 enforces only the per-upload 10 MB cap at the origin (`MEDIA_MAX_UPLOAD_BYTES`). Rate limiting for `POST /admin/v1/media` and `GET /api/v1/media/**` is owned by S7 at the Cloudflare edge.

## Quality-gate findings and owners

Triage of the S3 gate warnings. Severity is `High`, `Medium`, or `Low`. "Closed" means already fixed or already owned by a future spec; nothing is dropped.

| # | Gap | Severity | Evidence | Action / owner |
|---|-----|----------|----------|----------------|
| 1 | No regression test for WebP codec selection (native reader risk) | Medium | `ImageProcessor.java:170-178,298-306` | FIXED: `ImageProcessorTest.selectsPureJavaWebpReaderAndSejdaWriter` asserts TwelveMonkeys reader + Sejda writer. |
| 2 | Orphan media files on post-commit failure/crash; no reconciliation sweep | Medium | `MediaService.java:81-87,145-153` | ROUTED to S7 "Media hardening ownership": reconciliation sweep + runbook. |
| 3 | No rate limit on media upload or public media GET | Medium | `MediaController.java:37-62`; `SecurityConfig.java:72` | ROUTED to S7 rate limits; ownership documented above. |
| 4 | Unbounded nested `customizationGroups`/`options` collections | Medium | `CatalogApi.java:43,65`; `openapi.yaml:1863-1865,1900-1903` (no `maxItems`) | ROUTED to S7 (add `maxItems` + `@Size(max)` alongside the 1 MB JSON cap); S4 keeps offering snapshots consistent. |
| 5 | Media-in-use check race under READ_COMMITTED (template create vs media delete) | Medium | `MediaService.java:129`; `ItemTemplateService.java:127`; `ItemTemplateRepository.java:13` | ROUTED to S7: enforce reference integrity under concurrency; S4 extends to offering photos. |
| 6 | Immutable year-long cache delays privacy takedown | Medium | `MediaController.java:28-29` | Documented above; ROUTED to S7: purge on delete or shorten TTL. |
| 7 | Broad public path matcher | Closed | `SecurityConfig.java:72` (GET-only matcher) | Already fixed in prior gate. |
| 8 | `local` profile exposes mTLS-bypassing API on all interfaces | High if mis-deployed | `application-local.yml:24`; `SecurityConfig.java:79-81` | Already owned by S7 (`S7-hardening-deploy.md:53-54,278`): prod rejects local bypass. |
| 9 | Original filename retained in DB (PII) | Low | `Media.java:26-27`; `V3__catalog.sql:6`; `MediaService.java:75,198-202` | ROUTED to S7: confirm retention need; keep sanitized, never returned or logged. |
| 10 | Stale S1 deployment text | Closed | `S1-foundation.md:105` | Already fixed in prior gate. |
| 11 | Mutable image dependency tags | Low | `build.gradle.kts` (version strings for the four image deps) | ROUTED to S7: digest/verification-metadata pinning and scan. |
| 12 | 10 MB proxy upload cap | Medium (deferred) | `S3-catalog-media.md:135`; `S7-hardening-deploy.md:87-90,159` | Already owned by S7; server side done in S3. |
| 13 | Lint tooling gaps (Spotless/Checkstyle/PMD, yamllint, hadolint, OpenAPI validator) | Low | Gate 2 result; no such config in repo | ROUTED to S7 "Developer tooling". |
| 14 | No JaCoCo coverage tooling | Low | `build.gradle.kts` (no coverage plugin) | ROUTED to S7 "Developer tooling". |
| 15 | Media URL built in two places | Low | former `MediaService.toMediaView` / `ItemTemplateService.toMediaView` | FIXED: extracted `MediaViews`; both services delegate to `MediaViews.of`. |
| 16 | Untested nested IDs/boundaries; silent >60-char tag drop | Low-Medium | `CatalogIntegrationTest.java`; `ItemTemplateService.java:115-124,320-329` | FIXED: added tests for unknown/duplicate nested IDs, `available=false` round-trip, >60-char tag rejection, isolated decode-pixel bound; service now rejects over-length tags instead of dropping them. |

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
