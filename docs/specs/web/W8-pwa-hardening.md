# W8 — PWA, Accessibility, E2E, and Security Hardening

- Status: ready
- Depends on: W1–W7, S6
- Contract: all customer operations
- Next: production readiness

## Goal

Finish the customer web app as an installable, accessible, secure PWA with production build/serving, full end-to-end coverage, and no offline ordering.

## Non-goals

- Offline order placement or offline payment.
- Push notifications (email/SMS only).
- Native app wrappers.
- Analytics/tracking.

## PWA

- Web app manifest:
  - name, short name, description
  - start URL `/`
  - display `standalone`
  - theme/background colors
  - icons 192/512, maskable
- Service worker:
  - precache app shell and hashed static assets.
  - cache-first for immutable assets.
  - network-first for navigation with offline fallback.
  - never cache `/api/v1/menu-dates`, order data, auth, or media without explicit cache headers.
  - offline page explains that ordering requires a connection.
- Update flow:
  - detect new service worker, show "Update available", activate on confirm.
  - avoid stale bundles after deploy.
- Install prompt:
  - non-intrusive, dismissible, remembers dismissal.
- iOS:
  - meta tags, safe-area insets, apple-touch-icon.
- Responsive:
  - 360 px, tablets, desktop.
  - no horizontal scroll.
  - safe-area padding for notches.
- Images:
  - responsive `srcset`/`sizes` built from each offering's `photoVariants` keys `400`, `800`, `1600`.
  - fall back to `photoUrl` when variants are missing.
  - lazy loading below the fold.
  - reserved aspect ratios to prevent CLS.

## Accessibility

- WCAG 2.1 AA target:
  - landmarks, headings, labels
  - keyboard reachability
  - visible focus
  - color contrast
  - error identification and announcements
  - dialog focus trap and restore
  - status changes announced via `aria-live`
  - reduced motion
  - 44 px targets
- Test with:
  - axe-core in Playwright.
  - keyboard-only manual pass.
  - screen-reader spot check (VoiceOver/TalkBack if available).

## Security hardening

- CSP delivered by nginx/Cloudflare:
  - default-src 'self'
  - img-src 'self' data:
  - style-src 'self' (Tailwind build)
  - script-src 'self'
  - connect-src 'self'
  - frame-ancestors 'none'
  - base-uri 'self'
  - form-action 'self'
- No inline scripts/styles that break CSP. If Vite injects, use hashes/nonces only if required.
- Headers: HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`.
- External links: `rel="noopener noreferrer"`.
- No `dangerouslySetInnerHTML`.
- No tokens in storage.
- Dependency audit; document accepted risks.
- Validate all navigation params and return-to paths.
- Ensure API base URL is same-origin; no arbitrary host from query params.

## Production build and serving

- `web/Dockerfile`: multi-stage Node build → nginx Alpine.
- `web/nginx.conf`:
  - SPA fallback to `index.html`.
  - `/api` proxy to `api:8080`.
  - immutable cache for hashed assets.
  - `index.html` no-store.
  - 10 MB upload limit (for future admin paths not served here; harmless).
  - security headers and CSP.
  - gzip enabled.
- Build-time `VITE_API_BASE_URL` must be empty or the public origin.
- No source maps in production unless explicitly enabled.
- Bundle budget:
  - initial JS < 250 KB gzipped; investigate if exceeded.
- Error boundary with a support link and request ID when available.

## End-to-end suite

Playwright against local/staging:

1. Menu loads with seeded data.
2. OTP login (local helper).
3. Browse dates/windows, sold-out states.
4. Item customizations and price changes.
5. Add to cart, cross-date reset.
6. Checkout validation and sold-out race.
7. Order confirmation and Venmo instructions.
8. Status transitions: pending → accepted → ready → completed.
9. Rejected/cancelled/expired states.
10. History, cancel, preferences, SMS consent.
11. Logout and protected route redirect.
12. 404 and API error states.
13. Mobile viewport.
14. Offline page.
15. axe accessibility checks on main routes.

## Ordered tasks

1. Add manifest, icons, theme, iOS meta.
2. Configure service worker and offline fallback.
3. Add update-available flow.
4. Add install prompt.
5. Apply responsive image handling and layout polish.
6. Run a11y audit and fix issues.
7. Add CSP-compatible build output and nginx config.
8. Add production Dockerfile.
9. Add Playwright full suite and axe checks.
10. Add bundle budget and dependency audit.
11. Write `web/README.md` release/runbook notes.
12. Verify no offline ordering.

## Acceptance criteria

- Given a supported browser, then the app is installable with valid icons/manifest.
- Given offline navigation, then the offline page appears and ordering is unavailable.
- Given API data, then it is never served from the service worker cache.
- Given a new deploy, then the update prompt appears and activates on confirm.
- Given 360 px, then no horizontal scroll and sticky elements respect safe areas.
- Given keyboard-only, then all flows are reachable.
- Given axe on main routes, then no serious/critical violations.
- Given CSP, then the app loads without violations and no inline script errors.
- Given production build, then initial JS is under budget.
- Given the full Playwright suite, then all flows pass against seeded local data.
- Given a 404/500 API, then the UI shows a helpful state and request ID when available.

## Test plan

- Lighthouse PWA/accessibility/performance smoke.
- Playwright full suite on mobile and desktop viewports.
- axe checks.
- Offline/update behavior.
- CSP violation check in browser console.
- Bundle size check.
- Dependency audit.
- Manual iOS Safari and Android Chrome pass.

## Security checklist

- [ ] CSP enforced, no unsafe-inline.
- [ ] HSTS/security headers present in production.
- [ ] No tokens in JS-accessible storage.
- [ ] No secrets in bundle.
- [ ] External links safe.
- [ ] Service worker does not cache API/PII.
- [ ] Source maps absent in prod.
- [ ] Dependency audit reviewed.
- [ ] Return-to/navigation params validated.

## Quality gate

This phase is not complete until `/phase-gate W8` runs and blocking findings are fixed or explicitly deferred in this spec. The gate runs, in order:

1. `spec-compliance` — this spec's deliverables, API operations, migrations, acceptance criteria, test plan, security checklist, and definition of done are met; shortcuts flagged.
2. `lint` — applicable linters and secret scan on the changed scope.
3. `refactor` — readability and maintainability only, no behavior change.
4. `test-quality` — meaningful tests, edge cases, and coverage of critical invariants.
5. `security` — secure coding and deployment security audit.
6. `gap-analysis` — gaps fixed, routed to a new spec, or written into the owning future spec.

Quality-gate results belong in the phase PR description.

## Definition of done

The app is installable, accessible, CSP-compliant, fully covered by e2e tests, and served by the production nginx image. No offline ordering path exists.

## Handoff notes

- S7 wires the web container into production Compose and Cloudflare.
- Keep the service worker cache version tied to build hashes.
- Do not add analytics or third-party scripts without a spec update and privacy review.
